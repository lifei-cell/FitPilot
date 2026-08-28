package com.fitpilot.infrastructure.events;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class EventPayloads {
    private EventPayloads() {}

    public record WorkoutCompleted(long workoutId, long userId, LocalDateTime completedAt,
                                   int durationSeconds, int completedSetCount) {}

    public record PersonalRecordCreated(long personalRecordId, long userId, long exerciseId,
                                        String exerciseName, String recordType, BigDecimal score,
                                        long workoutId, LocalDateTime achievedAt) {}

    public record TrainingPlanChanged(long trainingPlanId, long userId, String status,
                                      int version, LocalDateTime changedAt) {}
}
