ALTER TABLE rag_eval_result
    ADD COLUMN expected_source_urls JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN actual_source_urls JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN citation_valid BOOLEAN NOT NULL DEFAULT FALSE;
