CREATE TABLE outbox_event (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    topic VARCHAR(160) NOT NULL,
    event_key VARCHAR(160) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED')),
    retry_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claimed_at TIMESTAMP,
    last_error VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMP
);
CREATE INDEX idx_outbox_relay ON outbox_event(status, next_attempt_at, id)
    WHERE status IN ('PENDING', 'SENDING');

CREATE TABLE processed_event (
    event_id UUID NOT NULL,
    consumer VARCHAR(120) NOT NULL,
    processed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_id, consumer)
);
CREATE INDEX idx_processed_event_time ON processed_event(processed_at);

CREATE TABLE dead_letter_event (
    id UUID PRIMARY KEY,
    event_id UUID,
    original_topic VARCHAR(160) NOT NULL,
    event_key VARCHAR(160),
    payload TEXT NOT NULL,
    partition_id INT NOT NULL,
    offset_id BIGINT NOT NULL,
    failure_reason VARCHAR(2000) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'REPLAYED')),
    failed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    replayed_at TIMESTAMP
);
CREATE INDEX idx_dead_letter_status_time ON dead_letter_event(status, failed_at DESC);

CREATE TABLE workout_analytics_projection (
    workout_id BIGINT PRIMARY KEY REFERENCES workout(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    completed_at TIMESTAMP NOT NULL,
    duration_seconds INT NOT NULL,
    training_volume NUMERIC(16,2) NOT NULL DEFAULT 0,
    completed_set_count INT NOT NULL DEFAULT 0,
    projected_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_workout_analytics_user_time
    ON workout_analytics_projection(user_id, completed_at DESC);

CREATE TABLE user_notification (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_event_id UUID NOT NULL UNIQUE,
    type VARCHAR(50) NOT NULL,
    title VARCHAR(120) NOT NULL,
    message VARCHAR(500) NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_notification_user_time ON user_notification(user_id, created_at DESC);
