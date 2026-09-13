-- Retain the original acknowledgement even if a set is later edited or deleted.
CREATE TABLE workout_set_receipt (
    workout_id BIGINT NOT NULL REFERENCES workout(id) ON DELETE CASCADE,
    operation_id UUID NOT NULL,
    workout_exercise_id BIGINT NOT NULL REFERENCES workout_exercise(id) ON DELETE CASCADE,
    request_body JSONB NOT NULL,
    response_body JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (workout_id, operation_id)
);
CREATE INDEX idx_workout_set_receipt_exercise ON workout_set_receipt(workout_exercise_id);
