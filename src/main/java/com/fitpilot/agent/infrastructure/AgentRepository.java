package com.fitpilot.agent.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.agent.dto.AgentDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Repository
public class AgentRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public AgentRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public void createSession(UUID id, long userId, LocalDateTime now) {
        jdbc.update("INSERT INTO agent_session(id,user_id,created_at,updated_at) VALUES (?,?,?,?)", id, userId, now, now);
    }
    public boolean ownsSession(UUID id, long userId) {
        Long count=jdbc.queryForObject("SELECT count(*) FROM agent_session WHERE id=? AND user_id=? AND status='ACTIVE'",Long.class,id,userId);
        return count!=null&&count>0;
    }
    public void touchSession(UUID id) { jdbc.update("UPDATE agent_session SET updated_at=now() WHERE id=?", id); }
    public void startExecution(UUID id, long userId, UUID sessionId, String intent, List<String> tools, LocalDateTime now) {
        jdbc.update("INSERT INTO agent_execution(id,user_id,session_id,intent,selected_tools,status,model,created_at) VALUES (?,?,?,?,?::jsonb,'RUNNING','RULE_WORKFLOW',?)",
                id, userId, sessionId, intent, write(tools), now);
    }
    public void updateDecision(UUID id, String intent, List<String> tools) {
        jdbc.update("UPDATE agent_execution SET intent=?,selected_tools=?::jsonb WHERE id=?",intent,write(tools),id);
    }
    public void addLlmUsage(UUID id, String model, String promptVersion, boolean degraded,
                            int inputTokens, int outputTokens, BigDecimal cost) {
        jdbc.update("""
                UPDATE agent_execution SET model=?,prompt_version=?,degraded=(degraded OR ?),
                  input_tokens=input_tokens+?,output_tokens=output_tokens+?,cost_usd=cost_usd+?
                WHERE id=?
                """,model,promptVersion,degraded,inputTokens,outputTokens,cost,id);
    }
    public void finishExecution(UUID id, String status, long latency, int violations) {
        jdbc.update("UPDATE agent_execution SET status=?,latency_ms=?,rule_violation_count=?,completed_at=now() WHERE id=?",
                status, latency, violations, id);
    }
    public void toolCall(UUID executionId, String name, Object request, Object response, String status, long latency) {
        jdbc.update("INSERT INTO agent_tool_call(id,execution_id,tool_name,request_payload,response_payload,status,latency_ms,created_at) VALUES (?,?,?,?::jsonb,?::jsonb,?,?,now())",
                UUID.randomUUID(), executionId, name, write(request), write(response), status, latency);
    }
    public void createPending(UUID id, UUID executionId, long userId, String tool, Object payload, String hash,
                              Instant expiresAt, LocalDateTime now) {
        jdbc.update("INSERT INTO agent_pending_action(id,execution_id,user_id,tool_name,payload,confirmation_hash,status,expires_at,created_at) VALUES (?,?,?,?,?::jsonb,?,'AWAITING_CONFIRMATION',?,?)",
                id, executionId, userId, tool, write(payload), hash, expiresAt.atOffset(ZoneOffset.UTC), now);
    }
    public Optional<Pending> lockPending(UUID id, long userId) {
        return jdbc.query("SELECT execution_id,tool_name,payload::text,confirmation_hash,status,expires_at FROM agent_pending_action WHERE id=? AND user_id=? FOR UPDATE",
                rs -> rs.next() ? Optional.of(new Pending((UUID) rs.getObject(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getObject(6, java.time.OffsetDateTime.class).toInstant())) : Optional.empty(), id, userId);
    }
    public void markExecuted(UUID id) { jdbc.update("UPDATE agent_pending_action SET status='EXECUTED',executed_at=now() WHERE id=?", id); }
    public void upsertMemory(long userId, String key, Object value) {
        jdbc.update("INSERT INTO agent_memory(user_id,memory_key,memory_value,updated_at) VALUES (?,?,?::jsonb,now()) ON CONFLICT(user_id,memory_key) DO UPDATE SET memory_value=excluded.memory_value,updated_at=now()",
                userId, key, write(value));
    }
    public List<AgentDtos.PreferenceView> memories(long userId) {
        return jdbc.query("SELECT memory_key,memory_value::text,updated_at FROM agent_memory WHERE user_id=? ORDER BY memory_key", (rs, n) ->
                new AgentDtos.PreferenceView(rs.getString(1), read(rs.getString(2), Object.class), rs.getTimestamp(3).toLocalDateTime()), userId);
    }
    public void label(UUID id, List<String> expected) {
        jdbc.update("UPDATE agent_execution SET expected_tools=?::jsonb WHERE id=?", write(expected), id);
    }
    public AgentDtos.EvaluationMetrics metrics() {
        return jdbc.query("""
                SELECT count(*), coalesce(avg(CASE WHEN status IN ('SUCCEEDED','AWAITING_CONFIRMATION') THEN 1.0 ELSE 0 END),0),
                  coalesce(avg(CASE WHEN rule_violation_count>0 THEN 1.0 ELSE 0 END),0),
                  count(expected_tools), coalesce(avg(CASE WHEN expected_tools IS NULL THEN NULL WHEN expected_tools=selected_tools THEN 1.0 ELSE 0 END),0)
                FROM agent_execution
                """, rs -> { rs.next(); return new AgentDtos.EvaluationMetrics(rs.getLong(1), round(rs.getDouble(2)),
                    round(rs.getDouble(3)), rs.getLong(4), round(rs.getDouble(5))); });
    }
    public <T> T read(String value, Class<T> type) { try { return json.readValue(value, type); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String write(Object value) { try { return json.writeValueAsString(value == null ? Map.of() : value); } catch (Exception e) { throw new IllegalArgumentException(e); } }
    private double round(double value) { return Math.round(value * 10000d) / 10000d; }
    public record Pending(UUID executionId, String tool, String payload, String confirmationHash, String status, Instant expiresAt) {}
}
