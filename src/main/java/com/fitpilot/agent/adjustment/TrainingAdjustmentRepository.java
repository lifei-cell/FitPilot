package com.fitpilot.agent.adjustment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.common.response.PageResult;
import com.fitpilot.infrastructure.events.WorkoutCompletedEventHandler;
import com.fitpilot.plan.dto.TrainingPlanDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
        // Use one statement snapshot so a receipt is only visible with the PRs it committed.
        return jdbc.queryForObject("""
                WITH recent_workouts AS (
                    SELECT id, completed_at >= CURRENT_TIMESTAMP - INTERVAL '14 days' AS current_period
                    FROM workout
                    WHERE user_id=? AND status='COMPLETED' AND completed_at >= CURRENT_TIMESTAMP - INTERVAL '28 days'
                ), recent_exercises AS (
                    SELECT we.id, we.target_sets, w.current_period
                    FROM workout_exercise we JOIN recent_workouts w ON w.id=we.workout_id
                ), working_sets AS (
                    SELECT count(*) AS completed_sets, coalesce(avg(ws.rpe),0) AS average_rpe,
                           coalesce(sum(coalesce(ws.weight_kg,0) * ws.reps) FILTER (WHERE e.current_period),0) AS current_volume,
                           coalesce(sum(coalesce(ws.weight_kg,0) * ws.reps) FILTER (WHERE NOT e.current_period),0) AS previous_volume
                    FROM workout_set ws JOIN recent_exercises e ON e.id=ws.workout_exercise_id
                    WHERE ws.completed_at IS NOT NULL AND NOT ws.is_warmup
                ), feedback AS (
                    SELECT count(*) AS feedback_count, coalesce(avg(fatigue_score),0) AS average_fatigue,
                           coalesce(max(pain_score) FILTER (WHERE updated_at >= CURRENT_TIMESTAMP - INTERVAL '7 days'),0) AS recent_pain
                    FROM workout_feedback
                    WHERE user_id=? AND updated_at >= CURRENT_TIMESTAMP - INTERVAL '28 days'
                )
                SELECT (SELECT count(*) FROM recent_workouts) AS completed_workouts,
                       (SELECT coalesce(sum(target_sets),0) FROM recent_exercises) AS target_sets,
                       working_sets.*, feedback.*,
                       (SELECT count(*) FROM personal_record
                        WHERE user_id=? AND achieved_at >= CURRENT_TIMESTAMP - INTERVAL '28 days') AS personal_records,
                       NOT EXISTS (
                           SELECT 1 FROM recent_workouts w
                           WHERE NOT EXISTS (
                               SELECT 1 FROM outbox_event e JOIN processed_event p ON p.event_id=e.event_id
                               WHERE e.aggregate_type='Workout' AND e.event_type='WorkoutCompletedEvent'
                                 AND e.aggregate_id=w.id::text AND p.consumer=?
                           )
                       ) AS pr_projection_complete
                FROM working_sets CROSS JOIN feedback
                """, (rs, row) -> new Metrics(rs.getLong("completed_workouts"), rs.getLong("completed_sets"),
                rs.getLong("target_sets"), rs.getDouble("average_rpe"), rs.getLong("feedback_count"),
                rs.getDouble("average_fatigue"), rs.getInt("recent_pain"), rs.getDouble("current_volume"),
                rs.getDouble("previous_volume"), rs.getLong("personal_records"), rs.getBoolean("pr_projection_complete")),
                userId, userId, userId, WorkoutCompletedEventHandler.PR_CONSUMER);
    }

    public boolean create(TrainingAdjustmentDtos.AdjustmentProposal proposal, long userId, UUID pendingActionId,
                          String model, boolean degraded, String promptVersion) {
        return jdbc.update("""
                INSERT INTO plan_adjustment(id,user_id,source_plan_id,source_plan_version,evidence,reasons,proposal,
                  rule,status,pending_action_id,model,prompt_version,degraded)
                VALUES (?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,'AWAITING_CONFIRMATION',?,?,?,?)
                ON CONFLICT (user_id,source_plan_id) WHERE status='AWAITING_CONFIRMATION' DO NOTHING
                """, proposal.adjustmentId(), userId, proposal.sourcePlanId(), proposal.sourcePlanVersion(),
                write(proposal.evidence()), write(proposal.reasons()), write(proposal.plan()), proposal.rule(),
                pendingActionId, model, promptVersion, degraded) == 1;
    }

    public boolean hasPending(long userId, long sourcePlanId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM plan_adjustment a JOIN agent_pending_action p
                      ON p.id=a.pending_action_id AND p.user_id=a.user_id
                    WHERE a.user_id=? AND a.source_plan_id=? AND a.status='AWAITING_CONFIRMATION'
                      AND p.tool_name='adjust_training_plan' AND p.status='AWAITING_CONFIRMATION'
                      AND p.expires_at>=CURRENT_TIMESTAMP
                )
                """, Boolean.class, userId, sourcePlanId));
    }

    public PendingProposal findPendingForSource(long userId, long sourcePlanId) {
        return jdbc.query("""
                SELECT id,pending_action_id FROM plan_adjustment
                WHERE user_id=? AND source_plan_id=? AND status='AWAITING_CONFIRMATION'
                """, rs -> rs.next() ? new PendingProposal(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class)) : null, userId, sourcePlanId);
    }

    public UUID findPendingAction(long userId, UUID adjustmentId) {
        return jdbc.query("""
                SELECT pending_action_id FROM plan_adjustment
                WHERE user_id=? AND id=? AND status='AWAITING_CONFIRMATION'
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, userId, adjustmentId);
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

    public boolean decide(long userId, UUID adjustmentId, Decision decision, Long draftPlanId) {
        return jdbc.update("""
                UPDATE plan_adjustment SET status=?,draft_plan_id=?,decided_at=CURRENT_TIMESTAMP
                WHERE id=? AND user_id=? AND status='AWAITING_CONFIRMATION'
                """, decision.name(), draftPlanId, adjustmentId, userId) == 1;
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

    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException(e); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private <T> T read(String value, TypeReference<T> type) {
        try { return json.readValue(value, type); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public enum Decision { ACCEPTED, REJECTED, STALE }

    public record PendingProposal(UUID id, UUID actionId) {}

    public record Metrics(long completedWorkouts, long completedSets, long targetSets, double averageRpe,
                          long feedbackCount, double averageFatigue, int latestPain, double currentVolume,
                          double previousVolume, long personalRecords, boolean prProjectionComplete) {}
}
