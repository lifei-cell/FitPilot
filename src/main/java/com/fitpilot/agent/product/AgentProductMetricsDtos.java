package com.fitpilot.agent.product;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AgentProductMetricsDtos {
    private AgentProductMetricsDtos() {}

    public record Snapshot(Instant generatedAt, int windowDays, int outcomeWindowDays,
                           SessionRetention sessionRetention, SuggestionFunnel suggestionFunnel,
                           Reliability reliability, List<ExecutionBreakdown> executionBreakdown,
                           TrainingOutcome trainingOutcome, MetricDefinitions definitions) {}

    public record MetricDefinitions(String sessionRetention, String acceptanceRate, String rejectionRate,
                                    String confirmationConversionRate, String ruleFallbackRate,
                                    String costPerSuccessfulExecution, String trainingOutcome) {}

    public record SessionRetention(long eligibleUsers, long retainedUsers, double rate,
                                   String definition) {}

    public record SuggestionFunnel(long generated, long accepted, long rejected, long pending, long stale,
                                   double acceptanceRate, double rejectionRate,
                                   double confirmationConversionRate) {}

    public record Reliability(long executions, long successfulExecutions, long ruleFallbackExecutions,
                              double ruleFallbackRate, BigDecimal totalCostUsd,
                              BigDecimal costPerSuccessfulExecutionUsd) {}

    public record ExecutionBreakdown(String intent, String model, String promptVersion, long executions,
                                     long successfulExecutions, long ruleFallbackExecutions,
                                     double successRate, double ruleFallbackRate,
                                     BigDecimal totalCostUsd, BigDecimal costPerSuccessfulExecutionUsd) {}

    public record TrainingOutcome(int followUpDays, long eligibleAdjustments, TrainingPeriod before,
                                  TrainingPeriod after, TrainingDelta delta, List<UUID> adjustmentIds) {}

    public record TrainingPeriod(long workouts, long completedWorkouts, long cancelledWorkouts,
                                 double completionRate, long painFeedbacks, double averagePain,
                                 BigDecimal trainingVolume, long personalRecords) {}

    public record TrainingDelta(double completionRate, double averagePain,
                                BigDecimal trainingVolume, double trainingVolumeRate,
                                long personalRecords) {}
}
