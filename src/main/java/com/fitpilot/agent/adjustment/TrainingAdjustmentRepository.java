package com.fitpilot.agent.adjustment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.common.response.PageResult;
import com.fitpilot.plan.dto.TrainingPlanDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class TrainingAdjustmentRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public TrainingAdjustmentRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Metrics metrics(long userId) {
        long completed = count("SELECT count(*) FROM workout WHERE user_id=? AND status='COMPLETED' AND completed_at>=CURRENT_TIMESTAMP-INTERVAL '28 days'", userId);
        long completedSets = count("""
                SELECT count(*) FROM workout_set ws JOIN workout_exercise we ON we.id=ws.workout_exercise_id
                JOIN workout w ON w.id=we.workout_id WHERE w.user_id=? AND w.status='COMPLETED'
                  AND w.completed_at>=CURRENT_TIMESTAMP-INTERVAL '28 days' AND ws.completed_at IS NOT NULL AND ws.is_warmup=FALSE
                """, userId);
        long targets = count("""
                SELECT coalesce(sum(we.target_sets),0) FROM workout_exercise we JOIN workout w ON w.id=we.workout_id
                WHERE w.user_id=? AND w.status='COMPLETED' AND w.completed_at>=CURRENT_TIMESTAMP-INTERVAL '28 days'
                """, userId);
        double rpe = decimal("""
                SELECT coalesce(avg(ws.rpe),0) FROM workout_set ws JOIN workout_exercise we ON we.id=ws.workout_exercise_id
                JOIN workout w ON w.id=we.workout_id WHERE w.user_id=? AND w.status='COMPLETED'
                  AND w.completed_at>=CURRENT_TIMESTAMP-INTERVAL '28 days' AND ws.completed_at IS NOT NULL
                  AND ws.is_warmup=FALSE AND ws.rpe IS NOT NULL
                """, userId);
        long feedbacks = count("SELECT count(*) FROM workout_feedback WHERE user_id=? AND updated_at>=CURRENT_TIMESTAMP-INTERVAL '28 days'", userId);
        double fatigue = decimal("SELECT coalesce(avg(fatigue_score),0) FROM workout_feedback WHERE user_id=? AND updated_at>=CURRENT_TIMESTAMP-INTERVAL '28 days'", userId);
        int pain = (int) count("SELECT coalesce(max(pain_score),0) FROM workout_feedback WHERE user_id=? AND updated_at>=CURRENT_TIMESTAMP-INTERVAL '7 days'", userId);
        double currentVolume = decimal("SELECT coalesce(sum(training_volume),0) FROM workout_analytics_projection WHERE user_id=? AND completed_at>=CURRENT_TIMESTAMP-INTERVAL '14 days'", userId);
        double previousVolume = decimal("SELECT coalesce(sum(training_volume),0) FROM workout_analytics_projection WHERE user_id=? AND completed_at>=CURRENT_TIMESTAMP-INTERVAL '28 days' AND completed_at<CURRENT_TIMESTAMP-INTERVAL '14 days'", userId);
        long prs = count("SELECT count(*) FROM personal_record WHERE user_id=? AND achieved_at>=CURRENT_TIMESTAMP-INTERVAL '28 days'", userId);
        return new Metrics(completed, completedSets, targets, rpe, feedbacks, fatigue, pain, currentVolume, previousVolume, prs);
    }

    public void create(TrainingAdjustmentDtos.AdjustmentProposal proposal, long userId, UUID pendingActionId,
                       String model, boolean degraded, String promptVersion) {
        jdbc.update("""
                UPDATE plan_adjustment a SET status='STALE',decided_at=CURRENT_TIMESTAMP
                WHERE a.user_id=? AND a.source_plan_id=? AND a.status='AWAITING_CONFIRMATION'
                  AND EXISTS (SELECT 1 FROM agent_pending_action p WHERE p.id=a.pending_action_id AND p.expires_at<CURRENT_TIMESTAMP)
                """, userId, proposal.sourcePlanId());
        jdbc.update("""
                INSERT INTO plan_adjustment(id,user_id,source_plan_id,source_plan_version,evidence,reasons,proposal,
                  rule,status,pending_action_id,model,prompt_version,degraded)
                VALUES (?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,'AWAITING_CONFIRMATION',?,?,?,?)
                """, proposal.adjustmentId(), userId, proposal.sourcePlanId(), proposal.sourcePlanVersion(),
                write(proposal.evidence()), write(proposal.reasons()), write(proposal.plan()), proposal.rule(),
                pendingActionId, model, promptVersion, degraded);
    }

    public boolean hasPending(long userId, long sourcePlanId) {
        jdbc.update("""
                UPDATE plan_adjustment a SET status='STALE',decided_at=CURRENT_TIMESTAMP
                WHERE a.user_id=? AND a.source_plan_id=? AND a.status='AWAITING_CONFIRMATION'
                  AND EXISTS (SELECT 1 FROM agent_pending_action p WHERE p.id=a.pending_action_id AND p.expires_at<CURRENT_TIMESTAMP)
                """, userId, sourcePlanId);
        Long value = jdbc.queryForObject("""
                SELECT count(*) FROM plan_adjustment a JOIN agent_pending_action p ON p.id=a.pending_action_id
                WHERE a.user_id=? AND a.source_plan_id=? AND a.status='AWAITING_CONFIRMATION'
                  AND p.status='AWAITING_CONFIRMATION' AND p.expires_at>=CURRENT_TIMESTAMP
                """,
                Long.class, userId, sourcePlanId);
        return value != null && value > 0;
    }

    public void createDecision(UUID id, long userId, long sourcePlanId, int sourceVersion, String rule,
                               TrainingAdjustmentDtos.Evidence evidence, List<String> reasons) {
        jdbc.update("""
                INSERT INTO plan_adjustment(id,user_id,source_plan_id,source_plan_version,evidence,reasons,rule,status,
                  model,prompt_version,degraded) VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?, 'RULE_WORKFLOW','adaptive-v1',FALSE)
                """, id, userId, sourcePlanId, sourceVersion, write(evidence), write(reasons), rule, rule);
    }

    public PageResult<TrainingAdjustmentDtos.AdjustmentView> list(long userId, long page, long size) {
        long total = count("SELECT count(*) FROM plan_adjustment WHERE user_id=?", userId);
        List<TrainingAdjustmentDtos.AdjustmentView> items = jdbc.query("""
                SELECT id,source_plan_id,source_plan_version,rule,status,evidence::text,reasons::text,proposal::text,
                  pending_action_id,draft_plan_id,model,degraded,prompt_version,created_at,decided_at
                FROM plan_adjustment WHERE user_id=? ORDER BY created_at DESC LIMIT ? OFFSET ?
                """, (rs, row) -> view(rs), userId, size, (page - 1) * size);
        return PageResult.of(items, total, page, size);
    }

    public TrainingAdjustmentDtos.AdjustmentView find(UUID id, long userId) {
        return jdbc.query("""
                SELECT id,source_plan_id,source_plan_version,rule,status,evidence::text,reasons::text,proposal::text,
                  pending_action_id,draft_plan_id,model,degraded,prompt_version,created_at,decided_at
                FROM plan_adjustment WHERE id=? AND user_id=?
                """, rs -> rs.next() ? view(rs) : null, id, userId);
    }

    public boolean reject(UUID id, long userId) {
        int changed = jdbc.update("UPDATE plan_adjustment SET status='REJECTED',decided_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND status='AWAITING_CONFIRMATION'", id, userId);
        if (changed == 1) jdbc.update("""
                UPDATE agent_pending_action SET status='REJECTED',executed_at=CURRENT_TIMESTAMP
                WHERE id=(SELECT pending_action_id FROM plan_adjustment WHERE id=?) AND user_id=?
                """, id, userId);
        return changed == 1;
    }

    public void accept(UUID adjustmentId, long draftPlanId) {
        jdbc.update("UPDATE plan_adjustment SET status='ACCEPTED',draft_plan_id=?,decided_at=CURRENT_TIMESTAMP WHERE id=?", draftPlanId, adjustmentId);
    }

    public void stale(UUID adjustmentId) {
        jdbc.update("UPDATE plan_adjustment SET status='STALE',decided_at=CURRENT_TIMESTAMP WHERE id=?", adjustmentId);
    }

    private TrainingAdjustmentDtos.AdjustmentView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        String proposal = rs.getString(8);
        java.time.OffsetDateTime decided = rs.getObject(15, java.time.OffsetDateTime.class);
        return new TrainingAdjustmentDtos.AdjustmentView(rs.getObject(1, UUID.class), rs.getLong(2), rs.getInt(3),
                rs.getString(4), rs.getString(5), read(rs.getString(6), TrainingAdjustmentDtos.Evidence.class),
                read(rs.getString(7), new TypeReference<List<String>>() {}), proposal == null ? null
                : read(proposal, TrainingPlanDtos.CreateRequest.class), rs.getObject(9, UUID.class),
                rs.getObject(10) == null ? null : rs.getLong(10), rs.getString(11), rs.getBoolean(12),
                rs.getString(13), rs.getObject(14, java.time.OffsetDateTime.class).toInstant(),
                decided == null ? null : decided.toInstant());
    }

    private long count(String sql, long userId) {
        Number value = jdbc.queryForObject(sql, Number.class, userId);
        return value == null ? 0 : value.longValue();
    }

    private double decimal(String sql, long userId) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, userId);
        return value == null ? 0 : value.doubleValue();
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException(e); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private <T> T read(String value, TypeReference<T> type) {
        try { return json.readValue(value, type); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public record Metrics(long completedWorkouts, long completedSets, long targetSets, double averageRpe,
                          long feedbackCount, double averageFatigue, int latestPain, double currentVolume,
                          double previousVolume, long personalRecords) {}
}
