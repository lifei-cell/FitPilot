package com.fitpilot.analytics.application;

import com.fitpilot.analytics.dto.AnalyticsDtos;
import com.fitpilot.analytics.infrastructure.AnalyticsMapper;
import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.exercise.repository.ExerciseRepository;
import com.fitpilot.pr.repository.PersonalRecordRepository;
import com.fitpilot.user.repository.BodyMetricRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

@Service
public class AnalyticsService {
    private final AnalyticsMapper mapper;
    private final PersonalRecordRepository personalRecords;
    private final ExerciseRepository exercises;
    private final BodyMetricRepository metrics;

    public AnalyticsService(AnalyticsMapper mapper, PersonalRecordRepository personalRecords,
                            ExerciseRepository exercises, BodyMetricRepository metrics) {
        this.mapper = mapper;
        this.personalRecords = personalRecords;
        this.exercises = exercises;
        this.metrics = metrics;
    }

    public AnalyticsDtos.Overview overview(long userId) {
        LocalDate monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDateTime start = monday.atStartOfDay();
        LocalDateTime end = start.plusWeeks(1);
        return new AnalyticsDtos.Overview(mapper.workoutCount(userId, start, end),
                mapper.trainingDurationSeconds(userId, start, end) / 60,
                mapper.trainingVolume(userId, start, end), personalRecords.countAchievedBetween(userId, start, end));
    }

    public List<AnalyticsDtos.ExerciseProgress> exerciseProgress(long userId, long exerciseId) {
        if (exercises.findActive(exerciseId).isEmpty()) {
            throw new BusinessException(ErrorCode.EXERCISE_NOT_FOUND, "exercise not found", HttpStatus.NOT_FOUND);
        }
        return mapper.exerciseProgress(userId, exerciseId).stream()
                .map(row -> new AnalyticsDtos.ExerciseProgress(row.date, row.maxWeight, row.estimated1rm)).toList();
    }

    public List<AnalyticsDtos.BodyWeightPoint> bodyWeightTrend(long userId, LocalDate startDate, LocalDate endDate) {
        LocalDate end = endDate == null ? LocalDate.now() : endDate;
        LocalDate start = startDate == null ? end.minusMonths(3) : startDate;
        if (start.isAfter(end)) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "startDate must not be after endDate");
        return metrics.findRange(userId, start.atStartOfDay(), end.plusDays(1).atStartOfDay().minusNanos(1)).stream()
                .map(m -> new AnalyticsDtos.BodyWeightPoint(m.recordedAt, m.weightKg, m.bodyFatPercentage, m.muscleMassKg)).toList();
    }
}
