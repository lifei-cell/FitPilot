ALTER TABLE rag_eval_run
    ADD COLUMN experiment_report JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE rag_eval_result
    ADD COLUMN experiment_profile VARCHAR(40) NOT NULL DEFAULT 'baseline';

ALTER TABLE rag_eval_result
    DROP CONSTRAINT rag_eval_result_run_id_case_id_key;

ALTER TABLE rag_eval_result
    ADD CONSTRAINT uq_rag_eval_result_profile_case
        UNIQUE (run_id, experiment_profile, case_id);

CREATE INDEX idx_rag_eval_result_run_profile
    ON rag_eval_result(run_id, experiment_profile);
