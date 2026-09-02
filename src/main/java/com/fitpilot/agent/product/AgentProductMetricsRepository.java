package com.fitpilot.agent.product;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class AgentProductMetricsRepository {
    private final JdbcTemplate jdbc;

    public AgentProductMetricsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RetentionRaw retention(int windowDays) {
        return jdbc.query("""
                WITH first_use AS (
                  SELECT s.user_id, MIN(m.created_at) AS first_at
                  FROM agent_message m JOIN agent_session s ON s.id=m.session_id
                  WHERE m.role='user' GROUP BY s.user_id
                ), cohort AS (
                  SELECT user_id, first_at FROM first_use
                  WHERE first_at>=CURRENT_TIMESTAMP-(? * INTERVAL '1 day')
                    AND first_at<CURRENT_TIMESTAMP-INTERVAL '7 days'
                )
                SELECT COUNT(*), COUNT(*) FILTER (WHERE EXISTS (
                  SELECT 1 FROM agent_message follow_up
                  JOIN agent_session follow_up_session ON follow_up_session.id=follow_up.session_id
                  WHERE follow_up_session.user_id=cohort.user_id AND follow_up.role='user'
                    AND follow_up.created_at>=date_trunc('day',cohort.first_at)+INTERVAL '1 day'
                    AND follow_up.created_at<date_trunc('day',cohort.first_at)+INTERVAL '8 days'
                )) FROM cohort
                """, rs -> {
            rs.next();
            return new RetentionRaw(rs.getLong(1), rs.getLong(2));
        }, windowDays);
    }

    public FunnelRaw suggestionFunnel(int windowDays) {
        return jdbc.query("""
                SELECT COUNT(*) FILTER (WHERE proposal IS NOT NULL),
                  COUNT(*) FILTER (WHERE proposal IS NOT NULL AND status='ACCEPTED'),
                  COUNT(*) FILTER (WHERE proposal IS NOT NULL AND status='REJECTED'),
                  COUNT(*) FILTER (WHERE proposal IS NOT NULL AND status='AWAITING_CONFIRMATION'),
                  COUNT(*) FILTER (WHERE proposal IS NOT NULL AND status='STALE')
                FROM plan_adjustment
                WHERE created_at>=CURRENT_TIMESTAMP-(? * INTERVAL '1 day')
                """, rs -> {
            rs.next();
            return new FunnelRaw(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                    rs.getLong(4), rs.getLong(5));
        }, windowDays);
    }

    public ReliabilityRaw reliability(int windowDays) {
        return jdbc.query("""
                SELECT COUNT(*),
                  COUNT(*) FILTER (WHERE status IN ('SUCCEEDED','AWAITING_CONFIRMATION')),
                  COUNT(*) FILTER (WHERE model='RULE_WORKFLOW'),
                  COALESCE(SUM(cost_usd),0)
                FROM agent_execution
                WHERE created_at>=CURRENT_TIMESTAMP-(? * INTERVAL '1 day')
                """, rs -> {
            rs.next();
            return new ReliabilityRaw(rs.getLong(1), rs.getLong(2), rs.getLong(3), money(rs.getBigDecimal(4)));
        }, windowDays);
    }

    public List<BreakdownRaw> executionBreakdown(int windowDays) {
        return jdbc.query("""
                SELECT intent, model, COALESCE(prompt_version,'unversioned'), COUNT(*),
                  COUNT(*) FILTER (WHERE status IN ('SUCCEEDED','AWAITING_CONFIRMATION')),
                  COUNT(*) FILTER (WHERE model='RULE_WORKFLOW'),
                  COALESCE(SUM(cost_usd),0)
                FROM agent_execution
                WHERE created_at>=CURRENT_TIMESTAMP-(? * INTERVAL '1 day')
                GROUP BY intent, model, COALESCE(prompt_version,'unversioned')
                ORDER BY COUNT(*) DESC, intent, model LIMIT 100
                """, (rs, row) -> new BreakdownRaw(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getLong(4), rs.getLong(5), rs.getLong(6), money(rs.getBigDecimal(7))), windowDays);
    }

    public OutcomeRaw trainingOutcome(int lookbackDays, int outcomeWindowDays) {
        List<UUID> adjustmentIds = jdbc.query("""
                SELECT a.id FROM plan_adjustment a JOIN training_plan p ON p.id=a.draft_plan_id
                WHERE a.status='ACCEPTED' AND a.decided_at>=CURRENT_TIMESTAMP-(? * INTERVAL '1 day')
                  AND p.started_at IS NOT NULL
                  AND p.started_at::timestamp+(? * INTERVAL '1 day')<=CURRENT_TIMESTAMP
                ORDER BY a.decided_at DESC, a.id LIMIT 100
                """, (rs, row) -> rs.getObject(1, UUID.class), lookbackDays, outcomeWindowDays);
        Map<Period, PeriodRaw> periods = new EnumMap<>(Period.class);
        jdbc.query("""
                WITH eligible AS (
                  SELECT a.id, a.user_id, p.started_at::timestamp AS anchor
                  FROM plan_adjustment a JOIN training_plan p ON p.id=a.draft_plan_id
                  WHERE a.status='ACCEPTED' AND a.decided_at>=CURRENT_TIMESTAMP-(? * INTERVAL '1 day')
                    AND p.started_at IS NOT NULL
                    AND p.started_at::timestamp+(? * INTERVAL '1 day')<=CURRENT_TIMESTAMP
                ), period_windows AS (
                  SELECT 'BEFORE' AS period, id, user_id,
                    anchor-(? * INTERVAL '1 day') AS starts_at, anchor AS ends_at FROM eligible
                  UNION ALL
                  SELECT 'AFTER', id, user_id, anchor,
                    anchor+(? * INTERVAL '1 day') FROM eligible
                ), per_adjustment AS (
                  SELECT pw.period, pw.id,
                    (SELECT COUNT(*) FROM workout w WHERE w.user_id=pw.user_id
                      AND w.started_at>=pw.starts_at AND w.started_at<pw.ends_at
                      AND w.status IN ('COMPLETED','CANCELLED')) AS workouts,
                    (SELECT COUNT(*) FROM workout w WHERE w.user_id=pw.user_id
                      AND w.started_at>=pw.starts_at AND w.started_at<pw.ends_at
                      AND w.status='COMPLETED') AS completed,
                    (SELECT COUNT(*) FROM workout w WHERE w.user_id=pw.user_id
                      AND w.started_at>=pw.starts_at AND w.started_at<pw.ends_at
                      AND w.status='CANCELLED') AS cancelled,
                    (SELECT COUNT(*) FROM workout_feedback f
                      JOIN workout w ON w.id=f.workout_id WHERE f.user_id=pw.user_id
                      AND w.started_at>=pw.starts_at AND w.started_at<pw.ends_at) AS pain_feedbacks,
                    (SELECT COALESCE(SUM(f.pain_score),0) FROM workout_feedback f
                      JOIN workout w ON w.id=f.workout_id WHERE f.user_id=pw.user_id
                      AND w.started_at>=pw.starts_at AND w.started_at<pw.ends_at) AS pain_total,
                    (SELECT COALESCE(SUM(a.training_volume),0) FROM workout_analytics_projection a
                      WHERE a.user_id=pw.user_id AND a.completed_at>=pw.starts_at AND a.completed_at<pw.ends_at) AS volume,
                    (SELECT COUNT(*) FROM personal_record pr WHERE pr.user_id=pw.user_id
                      AND pr.achieved_at>=pw.starts_at AND pr.achieved_at<pw.ends_at) AS records
                  FROM period_windows pw
                )
                SELECT period, COUNT(*), COALESCE(SUM(workouts),0), COALESCE(SUM(completed),0),
                  COALESCE(SUM(cancelled),0), COALESCE(SUM(pain_feedbacks),0),
                  COALESCE(SUM(pain_total),0), COALESCE(SUM(volume),0), COALESCE(SUM(records),0)
                FROM per_adjustment GROUP BY period
                """, rs -> {
            Period period = Period.valueOf(rs.getString(1));
            periods.put(period, new PeriodRaw(rs.getLong(2), rs.getLong(3), rs.getLong(4),
                    rs.getLong(5), rs.getLong(6), rs.getLong(7), money(rs.getBigDecimal(8)), rs.getLong(9)));
        }, lookbackDays, outcomeWindowDays, outcomeWindowDays, outcomeWindowDays);
        PeriodRaw empty = new PeriodRaw(0, 0, 0, 0, 0, 0, BigDecimal.ZERO, 0);
        PeriodRaw before = periods.getOrDefault(Period.BEFORE, empty);
        PeriodRaw after = periods.getOrDefault(Period.AFTER, empty);
        return new OutcomeRaw(Math.max(before.eligibleAdjustments(), after.eligibleAdjustments()), before, after,
                adjustmentIds);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public enum Period { BEFORE, AFTER }
    public record RetentionRaw(long eligibleUsers, long retainedUsers) {}
    public record FunnelRaw(long generated, long accepted, long rejected, long pending, long stale) {}
    public record ReliabilityRaw(long executions, long successful, long ruleFallback, BigDecimal totalCost) {}
    public record BreakdownRaw(String intent, String model, String promptVersion, long executions,
                               long successful, long ruleFallback, BigDecimal totalCost) {}
    public record PeriodRaw(long eligibleAdjustments, long workouts, long completed, long cancelled,
                            long painFeedbacks, long painTotal, BigDecimal trainingVolume, long personalRecords) {}
    public record OutcomeRaw(long eligibleAdjustments, PeriodRaw before, PeriodRaw after,
                             List<UUID> adjustmentIds) {}
}
