CREATE TABLE agent_session (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_agent_session_owner ON agent_session(user_id, updated_at DESC);

CREATE TABLE agent_memory (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    memory_key VARCHAR(80) NOT NULL,
    memory_value JSONB NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE(user_id, memory_key)
);

CREATE TABLE agent_execution (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id UUID NOT NULL REFERENCES agent_session(id) ON DELETE CASCADE,
    intent VARCHAR(40) NOT NULL,
    selected_tools JSONB NOT NULL DEFAULT '[]',
    expected_tools JSONB,
    status VARCHAR(20) NOT NULL,
    model VARCHAR(80) NOT NULL,
    input_tokens INTEGER NOT NULL DEFAULT 0,
    output_tokens INTEGER NOT NULL DEFAULT 0,
    latency_ms BIGINT,
    rule_violation_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP
);
CREATE INDEX idx_agent_execution_owner ON agent_execution(user_id, created_at DESC);

CREATE TABLE agent_tool_call (
    id UUID PRIMARY KEY,
    execution_id UUID NOT NULL REFERENCES agent_execution(id) ON DELETE CASCADE,
    tool_name VARCHAR(100) NOT NULL,
    request_payload JSONB NOT NULL,
    response_payload JSONB,
    status VARCHAR(20) NOT NULL,
    latency_ms BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_agent_tool_call_execution ON agent_tool_call(execution_id, created_at);

CREATE TABLE agent_pending_action (
    id UUID PRIMARY KEY,
    execution_id UUID NOT NULL REFERENCES agent_execution(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tool_name VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    confirmation_hash VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    executed_at TIMESTAMP
);
CREATE INDEX idx_agent_pending_owner ON agent_pending_action(user_id, created_at DESC);
