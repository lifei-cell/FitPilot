CREATE UNIQUE INDEX uk_workout_one_in_progress ON workout(user_id) WHERE status = 'IN_PROGRESS';
