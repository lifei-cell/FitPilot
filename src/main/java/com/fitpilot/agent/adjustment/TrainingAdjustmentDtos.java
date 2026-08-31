package com.fitpilot.agent.adjustment;

import com.fitpilot.plan.dto.TrainingPlanDtos;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TrainingAdjustmentDtos {
    private TrainingAdjustmentDtos() {}

    public record Evidence(int windowDays, long completedWorkouts, double planCompletionRate,
                           long completedWorkingSets, long targetWorkingSets, double setCompletionRate,
                           double averageRpe, long feedbackCount, double averageFatigue, int latestPain,
                           double currentVolume, double previousVolume, double volumeChangeRate,
                           long personalRecords) {}

    public record Context(long sourcePlanId, int sourcePlanVersion, String rule, boolean proposalAllowed,
                          Evidence evidence, List<String> reasons) {}

    public record AdjustmentProposal(UUID adjustmentId, long sourcePlanId, int sourcePlanVersion,
                                     String rule, Evidence evidence, List<String> reasons,
                                     TrainingPlanDtos.CreateRequest plan) {}

    public record AdjustmentView(UUID id, long sourcePlanId, int sourcePlanVersion, String rule, String status,
                                 Evidence evidence, List<String> reasons, TrainingPlanDtos.CreateRequest proposal,
                                 UUID pendingActionId, Long draftPlanId, String model, boolean degraded,
                                 String promptVersion, Instant createdAt, Instant decidedAt) {}
}
