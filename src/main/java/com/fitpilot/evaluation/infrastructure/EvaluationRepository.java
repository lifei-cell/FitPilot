package com.fitpilot.evaluation.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.evaluation.domain.EvaluationCases;
import com.fitpilot.evaluation.dto.EvaluationDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EvaluationRepository {
    public enum RunType { AGENT, RAG }

    public record PendingRun(UUID id, RunType type, String mode, List<EvaluationCases.RagCase> ragCases,
                             EvaluationCases.RagExperiment ragExperiment) {
        public PendingRun(UUID id, RunType type, String mode, List<EvaluationCases.RagCase> ragCases) {
            this(id, type, mode, ragCases, EvaluationCases.RagExperiment.none());
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public EvaluationRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void createAgent(UUID id, String dataset, String mode, String model, String prompt,
                            int timeoutSeconds) {
        jdbc.update("""
                INSERT INTO agent_eval_run(id,dataset_version,mode,model,prompt_version,status,queued_at,deadline_at,request_payload)
                VALUES (?,?,?,?,?,'QUEUED',now(),now() + (? * INTERVAL '1 second'),?::jsonb)
                """, id, dataset, mode, model, prompt, timeoutSeconds, write(Map.of("mode", mode)));
    }

    public void createRag(UUID id, String dataset, Map<String, Object> snapshot,
                          List<EvaluationCases.RagCase> cases, int timeoutSeconds) {
        createRag(id, dataset, snapshot, cases, "REGRESSION", EvaluationCases.RagExperiment.none(),
                timeoutSeconds);
    }

    public void createRagExperiment(UUID id, String dataset, Map<String, Object> snapshot,
                                    List<EvaluationCases.RagCase> cases,
                                    EvaluationCases.RagExperiment experiment, int timeoutSeconds) {
        createRag(id, dataset, snapshot, cases, "FEEDBACK_EXPERIMENT", experiment, timeoutSeconds);
    }

    private void createRag(UUID id, String dataset, Map<String, Object> snapshot,
                           List<EvaluationCases.RagCase> cases, String mode,
                           EvaluationCases.RagExperiment experiment, int timeoutSeconds) {
        jdbc.update("""
                INSERT INTO rag_eval_run(id,dataset_version,dataset_snapshot,status,queued_at,deadline_at,request_payload)
                VALUES (?,?,?::jsonb,'QUEUED',now(),now() + (? * INTERVAL '1 second'),?::jsonb)
                """, id, dataset, write(snapshot), timeoutSeconds,
                write(Map.of("mode", mode, "cases", cases, "experiment", experiment)));
    }

    public Optional<PendingRun> reserve(UUID id, RunType type, String workerId, int leaseSeconds) {
        int updated = jdbc.update("UPDATE " + table(type) + " SET heartbeat_at=now(), "
                        + "lease_expires_at=now() + (? * INTERVAL '1 second'), worker_id=? "
                        + "WHERE id=? AND status='QUEUED' AND deadline_at>now() "
                        + "AND (worker_id IS NULL OR lease_expires_at IS NULL OR lease_expires_at<now())",
                leaseSeconds, workerId, id);
        return updated == 1 ? loadPending(id, type, "QUEUED") : Optional.empty();
    }

    public boolean start(UUID id, RunType type, String workerId) {
        return jdbc.update("UPDATE " + table(type) + " SET status='RUNNING',started_at=COALESCE(started_at,now()),"
                        + "attempt_count=attempt_count+1,error_message=NULL "
                        + "WHERE id=? AND status='QUEUED' AND worker_id=? AND deadline_at>now()",
                id, workerId) == 1;
    }

    public boolean renewLease(UUID id, RunType type, String workerId, int leaseSeconds) {
        return jdbc.update("UPDATE " + table(type) + " SET heartbeat_at=now(), "
                        + "lease_expires_at=now() + (? * INTERVAL '1 second') "
                        + "WHERE id=? AND status IN ('QUEUED','RUNNING') AND worker_id=? AND deadline_at>now()",
                leaseSeconds, id, workerId) == 1;
    }

    public List<PendingRun> queued(int limit) {
        return jdbc.query("""
                SELECT id,'AGENT' AS type,request_payload::text FROM agent_eval_run
                 WHERE status='QUEUED' AND deadline_at>now() AND (worker_id IS NULL OR lease_expires_at IS NULL OR lease_expires_at<now())
                UNION ALL
                SELECT id,'RAG' AS type,request_payload::text FROM rag_eval_run
                 WHERE status='QUEUED' AND deadline_at>now() AND (worker_id IS NULL OR lease_expires_at IS NULL OR lease_expires_at<now())
                ORDER BY id LIMIT ?
                """, (rs, row) -> pending((UUID) rs.getObject(1), RunType.valueOf(rs.getString(2)), rs.getString(3)), limit);
    }

    public void requeueExpiredLeases() {
        for (RunType type : RunType.values()) {
            jdbc.update("UPDATE " + table(type) + " SET status='QUEUED',worker_id=NULL,lease_expires_at=NULL "
                    + "WHERE status='RUNNING' AND (lease_expires_at IS NULL OR lease_expires_at<now()) AND deadline_at>now()");
            jdbc.update("UPDATE " + table(type) + " SET worker_id=NULL,lease_expires_at=NULL "
                    + "WHERE status='QUEUED' AND worker_id IS NOT NULL "
                    + "AND (lease_expires_at IS NULL OR lease_expires_at<now()) AND deadline_at>now()");
        }
    }

    public List<UUID> timeoutExpired() {
        List<UUID> ids = new ArrayList<>();
        for (RunType type : RunType.values()) {
            ids.addAll(jdbc.query("UPDATE " + table(type) + " SET status='TIMED_OUT', "
                            + "error_message='evaluation deadline exceeded',completed_at=now(),worker_id=NULL,lease_expires_at=NULL "
                            + "WHERE status IN ('QUEUED','RUNNING') AND deadline_at<=now() RETURNING id",
                    (rs, row) -> (UUID) rs.getObject(1)));
        }
        return ids;
    }

    public void reject(UUID id, RunType type, String workerId, String message) {
        jdbc.update("UPDATE " + table(type) + " SET status='REJECTED',error_message=?,completed_at=now(),"
                        + "worker_id=NULL,lease_expires_at=NULL WHERE id=? AND status='QUEUED' AND worker_id=?",
                safe(message), id, workerId);
    }

    public void agentResult(UUID run, String caseId, String hash, List<String> expected, List<String> actual,
                            boolean selection, boolean success, boolean violation, boolean hallucination, long latency) {
        jdbc.update("""
                INSERT INTO agent_eval_result(run_id,case_id,query_hash,expected_tools,actual_tools,tool_selection_correct,task_success,constraint_violation,hallucination,latency_ms)
                VALUES (?,?,?,?::jsonb,?::jsonb,?,?,?,?,?)
                ON CONFLICT(run_id,case_id) DO UPDATE SET query_hash=EXCLUDED.query_hash,
                expected_tools=EXCLUDED.expected_tools,actual_tools=EXCLUDED.actual_tools,
                tool_selection_correct=EXCLUDED.tool_selection_correct,task_success=EXCLUDED.task_success,
                constraint_violation=EXCLUDED.constraint_violation,hallucination=EXCLUDED.hallucination,
                latency_ms=EXCLUDED.latency_ms
                """, run, caseId, hash, write(expected), write(actual), selection, success, violation, hallucination, latency);
    }

    public void ragResult(UUID run, String caseId, String hash, List<String> expectedSources, List<String> actualSources,
                          double recall, double rr, double ndcg, double precision, double contextRecall,
                          boolean citationValid, long latency) {
        ragResult(run, "baseline", caseId, hash, expectedSources, actualSources, recall, rr, ndcg,
                precision, contextRecall, citationValid, latency);
    }

    public void ragResult(UUID run, String profile, String caseId, String hash,
                          List<String> expectedSources, List<String> actualSources,
                          double recall, double rr, double ndcg, double precision, double contextRecall,
                          boolean citationValid, long latency) {
        jdbc.update("""
                INSERT INTO rag_eval_result(run_id,experiment_profile,case_id,query_hash,expected_source_urls,actual_source_urls,recall_at_5,reciprocal_rank,ndcg,context_precision,context_recall,citation_valid,latency_ms)
                VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?,?,?)
                ON CONFLICT(run_id,experiment_profile,case_id) DO UPDATE SET query_hash=EXCLUDED.query_hash,
                expected_source_urls=EXCLUDED.expected_source_urls,actual_source_urls=EXCLUDED.actual_source_urls,
                recall_at_5=EXCLUDED.recall_at_5,reciprocal_rank=EXCLUDED.reciprocal_rank,ndcg=EXCLUDED.ndcg,
                context_precision=EXCLUDED.context_precision,context_recall=EXCLUDED.context_recall,
                citation_valid=EXCLUDED.citation_valid,latency_ms=EXCLUDED.latency_ms
                """, run, profile, caseId, hash, write(expectedSources), write(actualSources), recall, rr, ndcg,
                precision, contextRecall, citationValid, latency);
    }

    public void finishAgent(UUID id, String workerId, int total, int passed,
                            Map<String, Double> metrics, String model) {
        jdbc.update("UPDATE agent_eval_run SET status='SUCCEEDED',total_cases=?,passed_cases=?,metrics=?::jsonb,"
                        + "model=?,completed_at=now(),worker_id=NULL,lease_expires_at=NULL "
                        + "WHERE id=? AND status='RUNNING' AND worker_id=?",
                total, passed, write(metrics), model, id, workerId);
    }

    public void finishRag(UUID id, String workerId, int total, int passed, Map<String, Double> metrics) {
        jdbc.update("UPDATE rag_eval_run SET status='SUCCEEDED',total_cases=?,passed_cases=?,metrics=?::jsonb,"
                        + "completed_at=now(),worker_id=NULL,lease_expires_at=NULL "
                        + "WHERE id=? AND status='RUNNING' AND worker_id=?",
                total, passed, write(metrics), id, workerId);
    }

    public void finishRagExperiment(UUID id, String workerId, int total, int passed,
                                    Map<String, Double> metrics, Map<String, Object> report) {
        jdbc.update("UPDATE rag_eval_run SET status='SUCCEEDED',total_cases=?,passed_cases=?,metrics=?::jsonb,"
                        + "experiment_report=?::jsonb,completed_at=now(),worker_id=NULL,lease_expires_at=NULL "
                        + "WHERE id=? AND status='RUNNING' AND worker_id=?",
                total, passed, write(metrics), write(report), id, workerId);
    }

    public void failRagGate(UUID id, String workerId, int total, int passed,
                            Map<String, Double> metrics, String message) {
        jdbc.update("UPDATE rag_eval_run SET status='FAILED',total_cases=?,passed_cases=?,metrics=?::jsonb,"
                        + "error_message=?,completed_at=now(),worker_id=NULL,lease_expires_at=NULL "
                        + "WHERE id=? AND status='RUNNING' AND worker_id=?",
                total, passed, write(metrics), safe(message), id, workerId);
    }

    public Map<String, Double> lastSuccessfulRagMetrics(UUID excluding) {
        return jdbc.query("SELECT metrics::text FROM rag_eval_run WHERE status='SUCCEEDED' AND id<>? "
                        + "AND experiment_report='{}'::jsonb "
                        + "ORDER BY completed_at DESC LIMIT 1",
                rs -> rs.next() ? readMetrics(rs.getString(1)) : Map.of(), excluding);
    }

    public void fail(UUID id, RunType type, String workerId, String message) {
        jdbc.update("UPDATE " + table(type) + " SET status='FAILED',error_message=?,completed_at=now(),"
                        + "worker_id=NULL,lease_expires_at=NULL WHERE id=? AND status='RUNNING' AND worker_id=?",
                safe(message), id, workerId);
    }

    public Optional<EvaluationDtos.RunView> find(UUID id) {
        Optional<EvaluationDtos.RunView> agent = jdbc.query("""
                SELECT id,dataset_version,mode,model,prompt_version,status,total_cases,passed_cases,metrics::text,
                       error_message,queued_at,started_at,deadline_at,completed_at,attempt_count,'{}'::text
                  FROM agent_eval_run WHERE id=?
                """, rs -> rs.next() ? Optional.of(view(rs, "AGENT")) : Optional.empty(), id);
        if (agent.isPresent()) return agent;
        return jdbc.query("""
                SELECT id,dataset_version,NULL,NULL,NULL,status,total_cases,passed_cases,metrics::text,
                       error_message,queued_at,started_at,deadline_at,completed_at,attempt_count,experiment_report::text
                  FROM rag_eval_run WHERE id=?
                """, rs -> rs.next() ? Optional.of(view(rs, "RAG")) : Optional.empty(), id);
    }

    private Optional<PendingRun> loadPending(UUID id, RunType type, String status) {
        return jdbc.query("SELECT request_payload::text FROM " + table(type) + " WHERE id=? AND status=?",
                rs -> rs.next() ? Optional.of(pending(id, type, rs.getString(1))) : Optional.empty(), id, status);
    }

    private PendingRun pending(UUID id, RunType type, String payload) {
        try {
            JsonNode root = json.readTree(payload);
            String mode = root.path("mode").asText("RULE_WORKFLOW");
            List<EvaluationCases.RagCase> cases = type == RunType.RAG
                    ? json.convertValue(root.path("cases"), new TypeReference<>() { }) : List.of();
            EvaluationCases.RagExperiment experiment = type == RunType.RAG && root.has("experiment")
                    ? json.treeToValue(root.path("experiment"), EvaluationCases.RagExperiment.class)
                    : EvaluationCases.RagExperiment.none();
            return new PendingRun(id, type, mode, cases, experiment);
        } catch (Exception exception) {
            throw new IllegalStateException("invalid evaluation request payload", exception);
        }
    }

    private EvaluationDtos.RunView view(ResultSet rs, String type) throws SQLException {
        return new EvaluationDtos.RunView((UUID) rs.getObject(1), type, rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7), rs.getInt(8),
                readMetrics(rs.getString(9)), rs.getString(10), time(rs, 11), time(rs, 12),
                time(rs, 13), time(rs, 14), rs.getInt(15), readReport(rs.getString(16)));
    }

    private LocalDateTime time(ResultSet rs, int index) throws SQLException {
        return rs.getTimestamp(index) == null ? null : rs.getTimestamp(index).toLocalDateTime();
    }

    private String table(RunType type) {
        return type == RunType.AGENT ? "agent_eval_run" : "rag_eval_run";
    }

    private Map<String, Double> readMetrics(String value) {
        try { return json.readValue(value, new TypeReference<>() { }); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private Map<String, Object> readReport(String value) {
        try { return json.readValue(value, new TypeReference<>() { }); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalArgumentException(exception); }
    }

    private String safe(String value) {
        if (value == null) return "evaluation failed";
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}
