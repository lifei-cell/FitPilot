ALTER TABLE knowledge_document
    ADD COLUMN corpus_scope VARCHAR(48) NOT NULL DEFAULT 'live'
        CHECK (corpus_scope IN ('live', 'legacy-evaluation') OR
               corpus_scope ~ '^evaluation:[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$');

-- Earlier runners appended the run UUID to the external ID and recorded it in metadata.
-- Check revisions as well because completed deletions clear the current metadata.
UPDATE knowledge_document d SET corpus_scope='legacy-evaluation'
WHERE EXISTS (
    SELECT 1 FROM knowledge_document_revision r
    WHERE r.document_id=d.id AND r.metadata ? 'evaluationRunId'
      AND (right(d.external_id, 37)='-' || (r.metadata->>'evaluationRunId')
           OR strpos(d.external_id, '-ev-' || (r.metadata->>'evaluationRunId') || '-') > 0)
) OR (
    d.metadata ? 'evaluationRunId'
    AND (right(d.external_id, 37)='-' || (d.metadata->>'evaluationRunId')
         OR strpos(d.external_id, '-ev-' || (d.metadata->>'evaluationRunId') || '-') > 0)
);

ALTER TABLE knowledge_document DROP CONSTRAINT knowledge_document_external_id_key,
    ADD CONSTRAINT uk_knowledge_document_corpus_external UNIQUE (corpus_scope, external_id);

-- Unscoped Elasticsearch entries are excluded until their live document is republished.
UPDATE knowledge_document SET index_status='PENDING', index_claim_token=NULL, indexed_at=NULL
WHERE corpus_scope='live' AND lifecycle_status='ACTIVE';
