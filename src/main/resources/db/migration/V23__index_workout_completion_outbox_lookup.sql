CREATE INDEX idx_outbox_workout_completion ON outbox_event(aggregate_id) INCLUDE (event_id)
    WHERE aggregate_type='Workout' AND event_type='WorkoutCompletedEvent';
