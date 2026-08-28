package com.fitpilot.workout.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class WorkoutDtos {
    private WorkoutDtos() {}

    public record CreateRequest(@NotNull @Positive Long trainingPlanId,
                                @NotNull @Positive Long trainingPlanDayId,
                                @Size(max = 100) String name, String notes) {}
    public record AddExerciseRequest(@NotNull @Positive Long exerciseId, String notes) {}
    public record SetRequest(@PositiveOrZero BigDecimal weightKg, @NotNull @Positive Integer reps,
                             @DecimalMin("1.0") @DecimalMax("10.0") BigDecimal rpe,
                             @DecimalMin("0.0") @DecimalMax("10.0") BigDecimal rir,
                             Boolean isWarmup, Boolean isFailure) {}
    public record SetView(long id, int setNumber, BigDecimal weightKg, Integer reps, BigDecimal rpe,
                          BigDecimal rir, boolean isWarmup, boolean isFailure, LocalDateTime completedAt) {}
    public record ExerciseView(long id, long exerciseId, String exerciseName, int sequence,
                               Integer targetSets, Integer targetRepsMin, Integer targetRepsMax,
                               BigDecimal targetRpe, Integer restSeconds, String notes, List<SetView> sets) {}
    public record WorkoutView(long id, Long trainingPlanId, Long trainingPlanDayId, String name, String status,
                              LocalDateTime startedAt, LocalDateTime completedAt, Integer durationSeconds,
                              String notes, List<ExerciseView> exercises) {}
    public record WorkoutSummary(long id, String name, String status, LocalDateTime startedAt,
                                 LocalDateTime completedAt, Integer durationSeconds) {}
    public record CompleteView(WorkoutView workout, int newPersonalRecords) {}
}
