ALTER TABLE agent_session
    ADD COLUMN title VARCHAR(120) NOT NULL DEFAULT '新对话',
    ADD COLUMN last_message_at TIMESTAMPTZ,
    ADD COLUMN archived_at TIMESTAMPTZ;

ALTER TABLE agent_session
    ADD CONSTRAINT ck_agent_session_status CHECK (status IN ('ACTIVE', 'ARCHIVED'));

CREATE TABLE agent_message (
    id BIGSERIAL PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES agent_session(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL CHECK (role IN ('user', 'assistant', 'system')),
    content TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'COMPLETED'
        CHECK (status IN ('COMPLETED', 'ERROR')),
    execution_id UUID REFERENCES agent_execution(id) ON DELETE SET NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_agent_message_session_cursor ON agent_message(session_id, id DESC);
CREATE INDEX idx_agent_session_retention ON agent_session(updated_at)
    WHERE status IN ('ACTIVE', 'ARCHIVED');
