package com.fitpilot.workout.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class WorkoutReceiptRepository {
    private final JdbcTemplate jdbc;

    public WorkoutReceiptRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Receipt> find(long workoutId, UUID operationId) {
        return jdbc.query("SELECT workout_exercise_id, request_body::text, response_body::text "
                        + "FROM workout_set_receipt WHERE workout_id=? AND operation_id=?",
                (rs, row) -> new Receipt(rs.getLong(1), rs.getString(2), rs.getString(3)),
                workoutId, operationId).stream().findFirst();
    }

    public void insert(long workoutId, UUID operationId, long exerciseId, String request, String response) {
        jdbc.update("INSERT INTO workout_set_receipt(workout_id,operation_id,workout_exercise_id,request_body,response_body) "
                        + "VALUES (?,?,?,?::jsonb,?::jsonb)", workoutId, operationId, exerciseId, request, response);
    }

    public record Receipt(long exerciseId, String request, String response) {}
}
