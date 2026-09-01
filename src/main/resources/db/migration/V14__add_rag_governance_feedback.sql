ALTER TABLE knowledge_document
    ADD COLUMN publisher VARCHAR(200),
    ADD COLUMN trust_level VARCHAR(20) NOT NULL DEFAULT 'COMMUNITY'
        CHECK (trust_level IN ('OFFICIAL','INTERNAL','PROFESSIONAL','COMMUNITY')),
    ADD COLUMN effective_from TIMESTAMP,
    ADD COLUMN expires_at TIMESTAMP,
    ADD COLUMN lifecycle_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (lifecycle_status IN ('ACTIVE','EXPIRED','REVOKED','DELETE_PENDING','DELETED')),
    ADD COLUMN deleted_at TIMESTAMP;

CREATE INDEX idx_knowledge_document_retrievable
    ON knowledge_document(lifecycle_status, expires_at, category);

CREATE TABLE knowledge_document_revision (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES knowledge_document(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    title VARCHAR(300) NOT NULL,
    category VARCHAR(80) NOT NULL,
    source_url VARCHAR(1000) NOT NULL,
    source_license VARCHAR(120) NOT NULL,
    publisher VARCHAR(200),
    trust_level VARCHAR(20) NOT NULL,
    effective_from TIMESTAMP,
    expires_at TIMESTAMP,
    format VARCHAR(20) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    raw_content TEXT NOT NULL,
    metadata JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(document_id, version)
);

INSERT INTO knowledge_document_revision(id,document_id,version,title,category,source_url,source_license,
  publisher,trust_level,effective_from,expires_at,format,content_hash,raw_content,metadata,created_at)
SELECT gen_random_uuid(),id,version,title,category,source_url,source_license,publisher,trust_level,
  effective_from,expires_at,format,content_hash,raw_content,metadata,created_at FROM knowledge_document;

CREATE TABLE rag_deletion_task (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL UNIQUE REFERENCES knowledge_document(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(1000),
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_rag_deletion_retry ON rag_deletion_task(status,next_attempt_at);

CREATE TABLE rag_retrieval (
    id UUID PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    query_hash CHAR(64) NOT NULL,
    query_text VARCHAR(1000) NOT NULL,
    category VARCHAR(80),
    retrieval_mode VARCHAR(30) NOT NULL,
    result_snapshot JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_rag_retrieval_user_created ON rag_retrieval(user_id,created_at DESC);

CREATE TABLE rag_feedback (
    id UUID PRIMARY KEY,
    retrieval_id UUID NOT NULL REFERENCES rag_retrieval(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_type VARCHAR(16) NOT NULL CHECK (target_type IN ('ANSWER','CITATION')),
    target_key VARCHAR(80) NOT NULL DEFAULT '',
    rating VARCHAR(20) NOT NULL CHECK (rating IN ('HELPFUL','NOT_HELPFUL')),
    reason VARCHAR(24) CHECK (reason IN ('IRRELEVANT','OUTDATED','WRONG_CITATION','UNSAFE','OTHER')),
    comment VARCHAR(1000),
    review_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (review_status IN ('PENDING','APPROVED','REJECTED')),
    correct_source_urls JSONB NOT NULL DEFAULT '[]'::jsonb,
    reviewed_by VARCHAR(120),
    reviewed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(retrieval_id,user_id,target_type,target_key)
);

CREATE TABLE rag_dynamic_eval_case (
    id UUID PRIMARY KEY,
    feedback_id UUID NOT NULL UNIQUE REFERENCES rag_feedback(id) ON DELETE CASCADE,
    query_text VARCHAR(1000) NOT NULL,
    expected_source_urls JSONB NOT NULL,
    category VARCHAR(80) NOT NULL,
    dataset_version BIGINT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_rag_dynamic_eval_version ON rag_dynamic_eval_case(active,dataset_version);

ALTER TABLE rag_eval_run ADD COLUMN dataset_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb;

UPDATE knowledge_document SET index_status='PENDING',indexed_at=NULL;
