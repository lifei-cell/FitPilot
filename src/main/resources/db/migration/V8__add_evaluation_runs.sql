CREATE TABLE agent_eval_run (
    id UUID PRIMARY KEY,
    dataset_version VARCHAR(40) NOT NULL,
    mode VARCHAR(30) NOT NULL,
    model VARCHAR(120) NOT NULL,
    prompt_version VARCHAR(40) NOT NULL,
    status VARCHAR(24) NOT NULL,
    total_cases INTEGER NOT NULL DEFAULT 0,
    passed_cases INTEGER NOT NULL DEFAULT 0,
    metrics JSONB NOT NULL DEFAULT '{}',
    error_message VARCHAR(500),
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP
);

CREATE TABLE agent_eval_result (
    id BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES agent_eval_run(id) ON DELETE CASCADE,
    case_id VARCHAR(80) NOT NULL,
    query_hash VARCHAR(64) NOT NULL,
    expected_tools JSONB NOT NULL,
    actual_tools JSONB NOT NULL,
    tool_selection_correct BOOLEAN NOT NULL,
    task_success BOOLEAN NOT NULL,
    constraint_violation BOOLEAN NOT NULL,
    hallucination BOOLEAN NOT NULL,
    latency_ms BIGINT NOT NULL,
    UNIQUE(run_id, case_id)
);
CREATE INDEX idx_agent_eval_result_run ON agent_eval_result(run_id);

CREATE TABLE rag_eval_run (
    id UUID PRIMARY KEY,
    dataset_version VARCHAR(40) NOT NULL,
    status VARCHAR(24) NOT NULL,
    total_cases INTEGER NOT NULL DEFAULT 0,
    passed_cases INTEGER NOT NULL DEFAULT 0,
    metrics JSONB NOT NULL DEFAULT '{}',
    error_message VARCHAR(500),
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP
);

CREATE TABLE rag_eval_result (
    id BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES rag_eval_run(id) ON DELETE CASCADE,
    case_id VARCHAR(80) NOT NULL,
    query_hash VARCHAR(64) NOT NULL,
    recall_at_5 NUMERIC(8, 6) NOT NULL,
    reciprocal_rank NUMERIC(8, 6) NOT NULL,
    ndcg NUMERIC(8, 6) NOT NULL,
    context_precision NUMERIC(8, 6) NOT NULL,
    context_recall NUMERIC(8, 6) NOT NULL,
    latency_ms BIGINT NOT NULL,
    UNIQUE(run_id, case_id)
);
CREATE INDEX idx_rag_eval_result_run ON rag_eval_result(run_id);

ALTER TABLE llm_invocation ALTER COLUMN execution_id DROP NOT NULL;
