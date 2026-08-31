CREATE TABLE workout_feedback (
    workout_id BIGINT PRIMARY KEY REFERENCES workout(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    fatigue_score SMALLINT NOT NULL CHECK (fatigue_score BETWEEN 1 AND 10),
    pain_score SMALLINT NOT NULL CHECK (pain_score BETWEEN 0 AND 10),
    notes VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_workout_feedback_owner_time ON workout_feedback(user_id, updated_at DESC);

CREATE TABLE plan_adjustment (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_plan_id BIGINT NOT NULL REFERENCES training_plan(id) ON DELETE CASCADE,
    source_plan_version INTEGER NOT NULL,
    evidence JSONB NOT NULL,
    reasons JSONB NOT NULL,
    proposal JSONB,
    rule VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN
        ('INSUFFICIENT_DATA','SAFETY_HOLD','NO_CHANGE','AWAITING_CONFIRMATION','ACCEPTED','REJECTED','STALE')),
    pending_action_id UUID REFERENCES agent_pending_action(id) ON DELETE SET NULL,
    draft_plan_id BIGINT REFERENCES training_plan(id) ON DELETE SET NULL,
    model VARCHAR(120) NOT NULL,
    prompt_version VARCHAR(40) NOT NULL,
    degraded BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at TIMESTAMPTZ
);
CREATE INDEX idx_plan_adjustment_owner_time ON plan_adjustment(user_id, created_at DESC);
CREATE UNIQUE INDEX uk_plan_adjustment_one_pending ON plan_adjustment(user_id, source_plan_id)
    WHERE status='AWAITING_CONFIRMATION';
