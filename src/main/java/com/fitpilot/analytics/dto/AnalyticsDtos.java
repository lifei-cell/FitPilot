package com.fitpilot.analytics.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public final class AnalyticsDtos {
    private AnalyticsDtos() {}

    public record Overview(long workoutsThisWeek, long trainingDurationMinutes,
                           BigDecimal trainingVolume, long personalRecords) {}
    public record ExerciseProgress(LocalDate date, BigDecimal maxWeight, BigDecimal estimated1rm) {}
    public record BodyWeightPoint(LocalDateTime recordedAt, BigDecimal weightKg,
                                  BigDecimal bodyFatPercentage, BigDecimal muscleMassKg) {}
}
