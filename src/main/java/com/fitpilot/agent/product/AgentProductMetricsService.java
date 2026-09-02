package com.fitpilot.agent.product;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Service
public class AgentProductMetricsService {
    static final String RETENTION_DEFINITION =
            "首次发送 Agent 消息后第 1-7 个自然日内再次发送消息；不足 7 天的用户不进入分母";
    private static final AgentProductMetricsDtos.MetricDefinitions DEFINITIONS =
            new AgentProductMetricsDtos.MetricDefinitions(
                    RETENTION_DEFINITION,
                    "已接受数 /（已接受数 + 已拒绝数）",
                    "已拒绝数 /（已接受数 + 已拒绝数）",
                    "已接受数 / 已生成建议数",
                    "RULE_WORKFLOW 执行数 / 全部 Agent 执行数",
                    "窗口内 Agent 总成本 /（SUCCEEDED + AWAITING_CONFIRMATION 执行数）",
                    "以已接受草案的激活日为锚点，聚合等长前后窗口；仅纳入后窗口已完整结束的调整");

    private final AgentProductMetricsRepository repository;

    public AgentProductMetricsService(AgentProductMetricsRepository repository) {
        this.repository = repository;
    }

    public AgentProductMetricsDtos.Snapshot snapshot(int windowDays, int outcomeWindowDays) {
        var retention = repository.retention(windowDays);
        var funnel = repository.suggestionFunnel(windowDays);
        var reliability = repository.reliability(windowDays);
        var outcome = repository.trainingOutcome(windowDays, outcomeWindowDays);
        var breakdown = repository.executionBreakdown(windowDays).stream().map(this::breakdown).toList();

        long decisions = funnel.accepted() + funnel.rejected();
        var before = period(outcome.before());
        var after = period(outcome.after());
        var delta = new AgentProductMetricsDtos.TrainingDelta(
                round(after.completionRate() - before.completionRate()),
                round(after.averagePain() - before.averagePain()),
                scale(after.trainingVolume().subtract(before.trainingVolume())),
                ratio(after.trainingVolume().subtract(before.trainingVolume()), before.trainingVolume()),
                after.personalRecords() - before.personalRecords());

        return new AgentProductMetricsDtos.Snapshot(Instant.now(), windowDays, outcomeWindowDays,
                new AgentProductMetricsDtos.SessionRetention(retention.eligibleUsers(), retention.retainedUsers(),
                        ratio(retention.retainedUsers(), retention.eligibleUsers()), RETENTION_DEFINITION),
                new AgentProductMetricsDtos.SuggestionFunnel(funnel.generated(), funnel.accepted(), funnel.rejected(),
                        funnel.pending(), funnel.stale(), ratio(funnel.accepted(), decisions),
                        ratio(funnel.rejected(), decisions), ratio(funnel.accepted(), funnel.generated())),
                new AgentProductMetricsDtos.Reliability(reliability.executions(), reliability.successful(),
                        reliability.ruleFallback(), ratio(reliability.ruleFallback(), reliability.executions()),
                        scale(reliability.totalCost()), cost(reliability.totalCost(), reliability.successful())),
                breakdown,
                new AgentProductMetricsDtos.TrainingOutcome(outcomeWindowDays, outcome.eligibleAdjustments(),
                        before, after, delta, outcome.adjustmentIds()),
                DEFINITIONS);
    }

    private AgentProductMetricsDtos.ExecutionBreakdown breakdown(
            AgentProductMetricsRepository.BreakdownRaw raw) {
        return new AgentProductMetricsDtos.ExecutionBreakdown(raw.intent(), raw.model(), raw.promptVersion(),
                raw.executions(), raw.successful(), raw.ruleFallback(), ratio(raw.successful(), raw.executions()),
                ratio(raw.ruleFallback(), raw.executions()), scale(raw.totalCost()),
                cost(raw.totalCost(), raw.successful()));
    }

    private AgentProductMetricsDtos.TrainingPeriod period(AgentProductMetricsRepository.PeriodRaw raw) {
        return new AgentProductMetricsDtos.TrainingPeriod(raw.workouts(), raw.completed(), raw.cancelled(),
                ratio(raw.completed(), raw.workouts()), raw.painFeedbacks(),
                ratio(raw.painTotal(), raw.painFeedbacks()),
                scale(raw.trainingVolume()), raw.personalRecords());
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0 : round((double) numerator / denominator);
    }

    private static double ratio(BigDecimal delta, BigDecimal baseline) {
        return baseline.signum() == 0 ? 0 : delta.divide(baseline, 8, RoundingMode.HALF_UP)
                .setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private static BigDecimal cost(BigDecimal total, long successes) {
        return successes == 0 ? BigDecimal.ZERO : total.divide(BigDecimal.valueOf(successes), 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }
}
