package com.fitpilot.workout.application;

import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.workout.dto.WorkoutDtos;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class WorkoutFeedbackService {
    private final JdbcTemplate jdbc;

    public WorkoutFeedbackService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public WorkoutDtos.FeedbackView upsert(long userId, long workoutId, WorkoutDtos.FeedbackRequest request) {
        String status = jdbc.query("SELECT status FROM workout WHERE id=? AND user_id=?",
                rs -> rs.next() ? rs.getString(1) : null, workoutId, userId);
        if (status == null) throw new BusinessException(ErrorCode.WORKOUT_NOT_FOUND, "workout not found", HttpStatus.NOT_FOUND);
        if (!"COMPLETED".equals(status)) {
            throw new BusinessException(ErrorCode.INVALID_WORKOUT_SET, "feedback requires a completed workout", HttpStatus.CONFLICT);
        }
        jdbc.update("""
                INSERT INTO workout_feedback(workout_id,user_id,fatigue_score,pain_score,notes)
                VALUES (?,?,?,?,?) ON CONFLICT(workout_id) DO UPDATE SET fatigue_score=EXCLUDED.fatigue_score,
                  pain_score=EXCLUDED.pain_score,notes=EXCLUDED.notes,updated_at=CURRENT_TIMESTAMP
                """, workoutId, userId, request.fatigueScore(), request.painScore(), request.notes());
        return find(userId, workoutId);
    }

    public WorkoutDtos.FeedbackView find(long userId, long workoutId) {
        WorkoutDtos.FeedbackView value = jdbc.query("""
                SELECT workout_id,fatigue_score,pain_score,notes,updated_at
                FROM workout_feedback WHERE workout_id=? AND user_id=?
                """, rs -> rs.next() ? new WorkoutDtos.FeedbackView(rs.getLong(1), rs.getInt(2), rs.getInt(3),
                rs.getString(4), rs.getObject(5, java.time.OffsetDateTime.class).toLocalDateTime()) : null,
                workoutId, userId);
        if (value == null) throw new BusinessException(ErrorCode.WORKOUT_NOT_FOUND, "workout feedback not found", HttpStatus.NOT_FOUND);
        return value;
    }
}
