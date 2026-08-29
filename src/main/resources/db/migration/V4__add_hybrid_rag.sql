CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE knowledge_document (
    id UUID PRIMARY KEY,
    external_id VARCHAR(160) NOT NULL UNIQUE,
    title VARCHAR(300) NOT NULL,
    category VARCHAR(80) NOT NULL,
    source_url VARCHAR(1000) NOT NULL,
    source_license VARCHAR(120) NOT NULL,
    format VARCHAR(20) NOT NULL CHECK (format IN ('MARKDOWN', 'TEXT')),
    content_hash CHAR(64) NOT NULL,
    raw_content TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    index_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (index_status IN ('PENDING', 'INDEXING', 'INDEXED', 'FAILED')),
    index_error VARCHAR(1000),
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    indexed_at TIMESTAMP
);

CREATE INDEX idx_knowledge_document_index_retry
    ON knowledge_document(index_status, updated_at)
    WHERE index_status IN ('PENDING', 'FAILED');
CREATE INDEX idx_knowledge_document_category ON knowledge_document(category);

CREATE TABLE knowledge_chunk (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES knowledge_document(id) ON DELETE CASCADE,
    parent_chunk_id UUID REFERENCES knowledge_chunk(id) ON DELETE CASCADE,
    chunk_type VARCHAR(10) NOT NULL CHECK (chunk_type IN ('PARENT', 'CHILD')),
    ordinal INTEGER NOT NULL,
    heading VARCHAR(300),
    content TEXT NOT NULL,
    lexical_text TEXT NOT NULL,
    embedding vector(384),
    char_count INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(document_id, chunk_type, ordinal)
);

CREATE INDEX idx_knowledge_chunk_document ON knowledge_chunk(document_id);
CREATE INDEX idx_knowledge_chunk_parent ON knowledge_chunk(parent_chunk_id) WHERE parent_chunk_id IS NOT NULL;
CREATE INDEX idx_knowledge_chunk_embedding_hnsw ON knowledge_chunk
    USING hnsw (embedding vector_cosine_ops) WHERE chunk_type = 'CHILD';
