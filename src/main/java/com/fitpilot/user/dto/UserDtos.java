package com.fitpilot.user.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public final class UserDtos {
    private UserDtos() {}

    public record ProfileUpdateRequest(
            @Min(0) @Max(2) Short gender,
            @Past LocalDate birthday,
            @DecimalMin("50.0") @DecimalMax("260.0") BigDecimal heightCm,
            @PositiveOrZero Integer trainingExperienceMonths,
            @Pattern(regexp = "MUSCLE_GAIN|FAT_LOSS|STRENGTH|GENERAL_FITNESS") String trainingGoal,
            @Min(1) @Max(7) Integer weeklyFrequency,
            @Min(15) @Max(300) Integer preferredDurationMinutes) {}

    public record UserProfileView(long id, String username, String email, Short gender, LocalDate birthday,
                                  BigDecimal heightCm, Integer trainingExperienceMonths, String trainingGoal,
                                  Integer weeklyFrequency, Integer preferredDurationMinutes) {}

    public record BodyMetricRequest(
            @NotNull @DecimalMin("20.0") @DecimalMax("500.0") BigDecimal weightKg,
            @DecimalMin("1.0") @DecimalMax("70.0") BigDecimal bodyFatPercentage,
            @DecimalMin("1.0") @DecimalMax("300.0") BigDecimal muscleMassKg,
            @PastOrPresent LocalDateTime recordedAt) {}

    public record BodyMetricView(long id, BigDecimal weightKg, BigDecimal bodyFatPercentage,
                                 BigDecimal muscleMassKg, LocalDateTime recordedAt) {}
}
