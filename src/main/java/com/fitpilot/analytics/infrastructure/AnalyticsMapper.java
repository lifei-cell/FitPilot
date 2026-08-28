package com.fitpilot.analytics.infrastructure;

import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AnalyticsMapper {
    @Select("SELECT COUNT(*) FROM workout WHERE user_id=#{userId} AND status='COMPLETED' " +
            "AND completed_at >= #{start} AND completed_at < #{end}")
    long workoutCount(long userId, LocalDateTime start, LocalDateTime end);

    @Select("SELECT COALESCE(SUM(duration_seconds), 0) FROM workout WHERE user_id=#{userId} AND status='COMPLETED' " +
            "AND completed_at >= #{start} AND completed_at < #{end}")
    long trainingDurationSeconds(long userId, LocalDateTime start, LocalDateTime end);

    @Select("SELECT COALESCE(SUM(ws.weight_kg * ws.reps), 0) FROM workout w " +
            "JOIN workout_exercise we ON we.workout_id=w.id JOIN workout_set ws ON ws.workout_exercise_id=we.id " +
            "WHERE w.user_id=#{userId} AND w.status='COMPLETED' AND w.completed_at >= #{start} AND w.completed_at < #{end} " +
            "AND ws.completed_at IS NOT NULL AND ws.is_warmup=FALSE")
    BigDecimal trainingVolume(long userId, LocalDateTime start, LocalDateTime end);

    @Select("SELECT CAST(w.completed_at AS DATE) AS date, MAX(ws.weight_kg) AS max_weight, " +
            "MAX(ROUND(ws.weight_kg * (1 + ws.reps / 30.0), 2)) AS estimated_1rm " +
            "FROM workout w JOIN workout_exercise we ON we.workout_id=w.id " +
            "JOIN workout_set ws ON ws.workout_exercise_id=we.id " +
            "WHERE w.user_id=#{userId} AND we.exercise_id=#{exerciseId} AND w.status='COMPLETED' " +
            "AND ws.completed_at IS NOT NULL AND ws.is_warmup=FALSE " +
            "GROUP BY CAST(w.completed_at AS DATE) ORDER BY date")
    List<ExerciseProgressRow> exerciseProgress(long userId, long exerciseId);

    class ExerciseProgressRow {
        public LocalDate date;
        public BigDecimal maxWeight;
        public BigDecimal estimated1rm;
    }
}
