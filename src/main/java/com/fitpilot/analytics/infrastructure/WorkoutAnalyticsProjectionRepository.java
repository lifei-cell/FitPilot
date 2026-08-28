package com.fitpilot.analytics.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WorkoutAnalyticsProjectionRepository {
    private final JdbcTemplate jdbc;
    public WorkoutAnalyticsProjectionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void project(long workoutId) {
        int changed = jdbc.update("""
                INSERT INTO workout_analytics_projection(workout_id, user_id, completed_at, duration_seconds,
                    training_volume, completed_set_count, projected_at)
                SELECT w.id, w.user_id, w.completed_at, w.duration_seconds,
                    COALESCE(SUM(CASE WHEN ws.completed_at IS NOT NULL AND ws.is_warmup=FALSE
                        THEN COALESCE(ws.weight_kg, 0) * ws.reps ELSE 0 END), 0),
                    COUNT(*) FILTER (WHERE ws.completed_at IS NOT NULL AND ws.reps > 0), CURRENT_TIMESTAMP
                FROM workout w
                JOIN workout_exercise we ON we.workout_id=w.id
                JOIN workout_set ws ON ws.workout_exercise_id=we.id
                WHERE w.id=? AND w.status='COMPLETED'
                GROUP BY w.id, w.user_id, w.completed_at, w.duration_seconds
                ON CONFLICT (workout_id) DO UPDATE SET
                    completed_at=EXCLUDED.completed_at,
                    duration_seconds=EXCLUDED.duration_seconds,
                    training_volume=EXCLUDED.training_volume,
                    completed_set_count=EXCLUDED.completed_set_count,
                    projected_at=CURRENT_TIMESTAMP
                """, workoutId);
        if (changed == 0) throw new IllegalStateException("completed workout not found for analytics projection: " + workoutId);
    }
}
