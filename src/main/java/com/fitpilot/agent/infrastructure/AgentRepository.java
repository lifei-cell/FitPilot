package com.fitpilot.agent.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.agent.dto.AgentDtos;
import com.fitpilot.agent.adjustment.TrainingAdjustmentDtos;
import com.fitpilot.common.response.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
    public boolean ownsAnySession(UUID id, long userId) {
        Long count=jdbc.queryForObject("SELECT count(*) FROM agent_session WHERE id=? AND user_id=?",Long.class,id,userId);
        return count!=null&&count>0;
    }
    public void touchSession(UUID id) { jdbc.update("UPDATE agent_session SET updated_at=now() WHERE id=?", id); }

    public PageResult<AgentDtos.SessionSummary> sessions(long userId, String status, long page, long size) {
        String normalized = status == null || status.isBlank() ? "ACTIVE" : status;
        Long total = jdbc.queryForObject("SELECT count(*) FROM agent_session WHERE user_id=? AND status=?",
                Long.class, userId, normalized);
        List<AgentDtos.SessionSummary> items = jdbc.query("""
                SELECT id,title,status,last_message_at,created_at,updated_at
                FROM agent_session WHERE user_id=? AND status=?
                ORDER BY COALESCE(last_message_at, created_at) DESC, id
                LIMIT ? OFFSET ?
                """, (rs, row) -> new AgentDtos.SessionSummary(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), rs.getObject(4, java.time.OffsetDateTime.class) == null ? null
                : rs.getObject(4, java.time.OffsetDateTime.class).toInstant(), rs.getTimestamp(5).toLocalDateTime(),
                rs.getTimestamp(6).toLocalDateTime()), userId, normalized, size, (page - 1) * size);
        return PageResult.of(items, total == null ? 0 : total, page, size);
    }

    public boolean updateSession(UUID id, long userId, String title, String status) {
        String normalizedTitle = title == null ? null : title.trim();
        return jdbc.update("""
                UPDATE agent_session SET title=COALESCE(?,title),status=COALESCE(?,status),
                  archived_at=CASE WHEN ?='ARCHIVED' THEN CURRENT_TIMESTAMP WHEN ?='ACTIVE' THEN NULL ELSE archived_at END,
                  updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND user_id=?
                """, normalizedTitle, status, status, status, id, userId) == 1;
    }

    public boolean deleteSession(UUID id, long userId) {
        return jdbc.update("DELETE FROM agent_session WHERE id=? AND user_id=?", id, userId) == 1;
    }

    @Transactional
    public AgentDtos.ConversationMessage appendMessage(UUID sessionId, String role, String content, String status,
                                                        UUID executionId, Map<String, Object> metadata) {
        UUID locked = jdbc.query("SELECT id FROM agent_session WHERE id=? FOR UPDATE",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, sessionId);
        if (locked == null) throw new IllegalStateException("agent session not found");
        Map<String, Object> safeMetadata = metadata == null ? Map.of() : metadata;
        AgentDtos.ConversationMessage message = jdbc.query("""
                INSERT INTO agent_message(session_id,role,content,status,execution_id,metadata)
                VALUES (?,?,?,?,?,?::jsonb) RETURNING id,created_at
                """, rs -> {
            rs.next();
            return new AgentDtos.ConversationMessage(rs.getLong(1), role, content, status, executionId,
                    safeMetadata, rs.getObject(2, java.time.OffsetDateTime.class).toInstant());
        }, sessionId, role, content, status, executionId, write(safeMetadata));
        String firstTitle = "user".equals(role) ? title(content) : null;
        if (firstTitle == null) {
            jdbc.update("UPDATE agent_session SET last_message_at=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    java.time.OffsetDateTime.ofInstant(message.createdAt(), ZoneOffset.UTC), sessionId);
        } else {
            jdbc.update("""
                    UPDATE agent_session SET last_message_at=?,updated_at=CURRENT_TIMESTAMP,
                      title=CASE WHEN title='新对话' THEN ? ELSE title END WHERE id=?
                    """, java.time.OffsetDateTime.ofInstant(message.createdAt(), ZoneOffset.UTC), firstTitle, sessionId);
        }
        return message;
    }

    public List<AgentDtos.ConversationMessage> recentMessages(UUID sessionId, int limit) {
        List<AgentDtos.ConversationMessage> result = jdbc.query("""
                SELECT id,role,content,status,execution_id,metadata::text,created_at
                FROM agent_message WHERE session_id=? ORDER BY id DESC LIMIT ?
                """, (rs, row) -> message(rs), sessionId, limit);
        Collections.reverse(result);
        return result;
    }

    public AgentDtos.MessagePage messageHistory(UUID sessionId, Long beforeId, int limit) {
        long cursor = beforeId == null ? Long.MAX_VALUE : beforeId;
        List<AgentDtos.ConversationMessage> descending = jdbc.query("""
                SELECT id,role,content,status,execution_id,metadata::text,created_at
                FROM agent_message WHERE session_id=? AND id<? ORDER BY id DESC LIMIT ?
                """, (rs, row) -> message(rs), sessionId, cursor, limit + 1);
        Long next = descending.size() > limit ? descending.get(limit - 1).id() : null;
        if (descending.size() > limit) descending = new ArrayList<>(descending.subList(0, limit));
        Collections.reverse(descending);
        return new AgentDtos.MessagePage(descending, next);
    }

    public long messageCount(UUID sessionId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM agent_message WHERE session_id=?", Long.class, sessionId);
        return count == null ? 0 : count;
    }

    public int deleteExpiredSessions(int retentionDays, int batchSize) {
        return jdbc.update("""
                WITH expired AS (
                  SELECT s.id FROM agent_session s
                  WHERE s.updated_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 day')
                    AND NOT EXISTS (SELECT 1 FROM agent_pending_action p
                      JOIN agent_execution e ON e.id=p.execution_id
                      WHERE e.session_id=s.id AND p.status='AWAITING_CONFIRMATION' AND p.expires_at>CURRENT_TIMESTAMP)
                  ORDER BY s.updated_at LIMIT ?
                ) DELETE FROM agent_session WHERE id IN (SELECT id FROM expired)
                """, retentionDays, batchSize);
    }

    public List<AgentDtos.PendingActionSummary> pendingActions(long userId, UUID sessionId) {
        return jdbc.query("""
                SELECT p.id,e.session_id,p.tool_name,p.status,p.expires_at,p.payload::text
                FROM agent_pending_action p JOIN agent_execution e ON e.id=p.execution_id
                WHERE p.user_id=? AND e.session_id=? AND p.status='AWAITING_CONFIRMATION'
                ORDER BY p.created_at DESC
                """, (rs, row) -> {
                    String tool = rs.getString(3);
                    Object preview = "adjust_training_plan".equals(tool)
                            ? read(rs.getString(6), TrainingAdjustmentDtos.AdjustmentProposal.class).plan()
                            : read(rs.getString(6), Object.class);
                    return new AgentDtos.PendingActionSummary(rs.getObject(1, UUID.class),
                            rs.getObject(2, UUID.class), tool, rs.getString(4),
                            rs.getObject(5, java.time.OffsetDateTime.class).toInstant(), preview);
                },
                userId, sessionId);
    }

    public boolean rotatePendingToken(UUID id, long userId, String hash, Instant expiresAt) {
        return jdbc.update("""
                UPDATE agent_pending_action SET confirmation_hash=?,expires_at=?
                WHERE id=? AND user_id=? AND status='AWAITING_CONFIRMATION'
                """, hash, java.time.OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC), id, userId) == 1;
    }
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
    public void markRejected(UUID id, long userId) {
        jdbc.update("UPDATE agent_pending_action SET status='REJECTED',executed_at=now() WHERE id=? AND user_id=? AND status='AWAITING_CONFIRMATION'", id, userId);
    }
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

    private AgentDtos.ConversationMessage message(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AgentDtos.ConversationMessage(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getObject(5, UUID.class), read(rs.getString(6), new TypeReference<Map<String, Object>>() {}),
                rs.getObject(7, java.time.OffsetDateTime.class).toInstant());
    }

    private <T> T read(String value, TypeReference<T> type) {
        try { return json.readValue(value, type); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String title(String content) {
        String normalized = content == null ? "新对话" : content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 40 ? normalized : normalized.substring(0, 40);
    }
}
