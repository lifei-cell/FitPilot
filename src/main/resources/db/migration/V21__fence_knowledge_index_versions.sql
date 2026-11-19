ALTER TABLE knowledge_document
    ADD COLUMN index_claim_token UUID,
    ADD COLUMN index_slot_count INTEGER NOT NULL DEFAULT 0 CHECK (index_slot_count >= 0);

UPDATE knowledge_document d SET index_slot_count = c.child_count
FROM (
    SELECT document_id, count(*)::integer AS child_count
    FROM knowledge_chunk WHERE chunk_type='CHILD' GROUP BY document_id
) c WHERE d.id=c.document_id;

UPDATE knowledge_document SET index_status='PENDING' WHERE index_status='INDEXING';

ALTER TABLE rag_deletion_task ADD COLUMN document_version INTEGER;
DELETE FROM rag_deletion_task t USING knowledge_document d
WHERE t.document_id=d.id AND d.lifecycle_status='ACTIVE';

UPDATE rag_deletion_task t
SET document_version=d.version, status=CASE WHEN t.status='RUNNING' THEN 'PENDING' ELSE t.status END
FROM knowledge_document d WHERE t.document_id=d.id;

ALTER TABLE rag_deletion_task ALTER COLUMN document_version SET NOT NULL;
