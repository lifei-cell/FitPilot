ALTER TABLE agent_execution
    ADD COLUMN prompt_version VARCHAR(40),
    ADD COLUMN degraded BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN cost_usd NUMERIC(14, 8) NOT NULL DEFAULT 0;

CREATE TABLE llm_invocation (
    id UUID PRIMARY KEY,
    execution_id UUID NOT NULL REFERENCES agent_execution(id) ON DELETE CASCADE,
    provider VARCHAR(80) NOT NULL,
    model VARCHAR(120) NOT NULL,
    task_type VARCHAR(40) NOT NULL,
    prompt_version VARCHAR(40) NOT NULL,
    status VARCHAR(32) NOT NULL,
    input_tokens INTEGER NOT NULL DEFAULT 0,
    output_tokens INTEGER NOT NULL DEFAULT 0,
    cost_usd NUMERIC(14, 8) NOT NULL DEFAULT 0,
    latency_ms BIGINT NOT NULL,
    http_status INTEGER,
    error_code VARCHAR(80),
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_llm_invocation_execution ON llm_invocation(execution_id, created_at);
CREATE INDEX idx_llm_invocation_operations ON llm_invocation(created_at DESC, status, model);
