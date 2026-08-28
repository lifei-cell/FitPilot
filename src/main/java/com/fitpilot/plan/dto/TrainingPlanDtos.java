package com.fitpilot.plan.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class TrainingPlanDtos {
    private TrainingPlanDtos() {}

    public record ExerciseRequest(
            @NotNull @Positive Long exerciseId,
            @NotNull @Positive Integer sequence,
            @NotNull @Positive Integer targetSets,
            @NotNull @Positive Integer targetRepsMin,
            @NotNull @Positive Integer targetRepsMax,
            @DecimalMin("1.0") @DecimalMax("10.0") BigDecimal targetRpe,
            @PositiveOrZero Integer restSeconds,
            @Size(max = 255) String notes) {}

    public record DayRequest(@NotNull @Positive Integer dayNumber, @Size(max = 100) String name,
                             String notes, @NotEmpty List<@Valid ExerciseRequest> exercises) {}

    public record CreateRequest(@NotBlank @Size(max = 100) String name, String description,
                                @Pattern(regexp = "MUSCLE_GAIN|FAT_LOSS|STRENGTH|GENERAL_FITNESS") String goal,
                                @Min(1) @Max(104) Integer durationWeeks,
                                @NotEmpty @Size(max = 7) List<@Valid DayRequest> days) {}

    public record ExerciseView(long id, long exerciseId, int sequence, int targetSets, int targetRepsMin,
                               int targetRepsMax, BigDecimal targetRpe, Integer restSeconds, String notes) {}
    public record DayView(long id, int dayNumber, String name, String notes, List<ExerciseView> exercises) {}
    public record PlanView(long id, String name, String description, String goal, Integer durationWeeks,
                           int daysPerWeek, String status, int version, LocalDate startedAt, LocalDate endedAt,
                           List<DayView> days) {}
    public record PlanSummary(long id, String name, String goal, Integer durationWeeks, int daysPerWeek,
                              String status, LocalDate startedAt) {}
}
