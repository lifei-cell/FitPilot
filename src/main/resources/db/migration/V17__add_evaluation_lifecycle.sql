ALTER TABLE agent_eval_run ALTER COLUMN started_at DROP NOT NULL;
ALTER TABLE agent_eval_run
    ADD COLUMN queued_at TIMESTAMP,
    ADD COLUMN deadline_at TIMESTAMP,
    ADD COLUMN heartbeat_at TIMESTAMP,
    ADD COLUMN lease_expires_at TIMESTAMP,
    ADD COLUMN worker_id VARCHAR(120),
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN request_payload JSONB NOT NULL DEFAULT '{}'::jsonb;
UPDATE agent_eval_run
SET queued_at = started_at,
    deadline_at = COALESCE(completed_at, started_at) + INTERVAL '1 day';
UPDATE agent_eval_run
SET status = 'FAILED', error_message = 'interrupted by evaluation lifecycle migration', completed_at = now()
WHERE status = 'RUNNING';
ALTER TABLE agent_eval_run ALTER COLUMN queued_at SET NOT NULL;
ALTER TABLE agent_eval_run ALTER COLUMN deadline_at SET NOT NULL;
ALTER TABLE agent_eval_run ADD CONSTRAINT chk_agent_eval_status
    CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','REJECTED','TIMED_OUT'));
CREATE INDEX idx_agent_eval_recovery ON agent_eval_run(status, lease_expires_at, deadline_at);

ALTER TABLE rag_eval_run ALTER COLUMN started_at DROP NOT NULL;
ALTER TABLE rag_eval_run
    ADD COLUMN queued_at TIMESTAMP,
    ADD COLUMN deadline_at TIMESTAMP,
    ADD COLUMN heartbeat_at TIMESTAMP,
    ADD COLUMN lease_expires_at TIMESTAMP,
    ADD COLUMN worker_id VARCHAR(120),
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN request_payload JSONB NOT NULL DEFAULT '{}'::jsonb;
UPDATE rag_eval_run
SET queued_at = started_at,
    deadline_at = COALESCE(completed_at, started_at) + INTERVAL '1 day';
UPDATE rag_eval_run
SET status = 'FAILED', error_message = 'interrupted by evaluation lifecycle migration', completed_at = now()
WHERE status = 'RUNNING';
ALTER TABLE rag_eval_run ALTER COLUMN queued_at SET NOT NULL;
ALTER TABLE rag_eval_run ALTER COLUMN deadline_at SET NOT NULL;
ALTER TABLE rag_eval_run ADD CONSTRAINT chk_rag_eval_status
    CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','REJECTED','TIMED_OUT'));
CREATE INDEX idx_rag_eval_recovery ON rag_eval_run(status, lease_expires_at, deadline_at);
