CREATE INDEX idx_agent_message_product_activity ON agent_message(created_at, session_id)
    WHERE role='user';

CREATE INDEX idx_agent_execution_product_window
    ON agent_execution(created_at DESC, status, model);

CREATE INDEX idx_plan_adjustment_product_window
    ON plan_adjustment(created_at DESC, status);

CREATE INDEX idx_plan_adjustment_accepted_outcome
    ON plan_adjustment(decided_at DESC, draft_plan_id)
    WHERE status='ACCEPTED';
