ALTER TABLE workout
    ADD COLUMN idempotency_key VARCHAR(128),
    ADD COLUMN request_fingerprint CHAR(64),
    ADD CONSTRAINT ck_workout_idempotency_pair CHECK (
        (idempotency_key IS NULL AND request_fingerprint IS NULL)
        OR (idempotency_key IS NOT NULL AND request_fingerprint IS NOT NULL)
    );

CREATE UNIQUE INDEX uk_workout_user_idempotency
    ON workout(user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
