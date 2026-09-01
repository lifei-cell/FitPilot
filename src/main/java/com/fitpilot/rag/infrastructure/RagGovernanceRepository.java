package com.fitpilot.rag.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.rag.dto.RagDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class RagGovernanceRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public RagGovernanceRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public UUID saveRetrieval(Long userId, String queryHash, String query, String category,
                              String mode, List<RagDtos.RetrievedContext> contexts) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rag_retrieval(id,user_id,query_hash,query_text,category,retrieval_mode,result_snapshot)
                VALUES (?,?,?,?,?,?,?::jsonb)
                """, id, userId, queryHash, query, category, mode, write(contexts));
        return id;
    }

    public boolean ownsRetrieval(UUID retrievalId, long userId) {
        Integer value = jdbc.queryForObject("SELECT count(*) FROM rag_retrieval WHERE id=? AND user_id=?",
                Integer.class, retrievalId, userId);
        return value != null && value == 1;
    }

    public boolean containsCitation(UUID retrievalId, String documentId) {
        Integer value = jdbc.queryForObject("SELECT count(*) FROM rag_retrieval WHERE id=? AND result_snapshot @> ?::jsonb",
                Integer.class, retrievalId, write(List.of(Map.of("documentId", documentId))));
        return value != null && value == 1;
    }

    @Transactional
    public RagDtos.FeedbackView upsertFeedback(UUID retrievalId, long userId, RagDtos.FeedbackRequest request) {
        String key = "ANSWER".equals(request.targetType()) || request.targetKey() == null
                ? "" : request.targetKey().trim();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rag_feedback(id,retrieval_id,user_id,target_type,target_key,rating,reason,comment)
                VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT(retrieval_id,user_id,target_type,target_key) DO UPDATE SET rating=EXCLUDED.rating,
                  reason=EXCLUDED.reason,comment=EXCLUDED.comment,review_status='PENDING',correct_source_urls='[]'::jsonb,
                  reviewed_by=NULL,reviewed_at=NULL,updated_at=CURRENT_TIMESTAMP
                """, id, retrievalId, userId, request.targetType(), key, request.rating(), request.reason(), request.comment());
        jdbc.update("""
                UPDATE rag_dynamic_eval_case SET active=FALSE WHERE feedback_id=(SELECT id FROM rag_feedback
                  WHERE retrieval_id=? AND user_id=? AND target_type=? AND target_key=?)
                """, retrievalId, userId, request.targetType(), key);
        return jdbc.query("""
                SELECT id,retrieval_id,target_type,target_key,rating,reason,comment,review_status,
                  correct_source_urls::text,updated_at FROM rag_feedback
                WHERE retrieval_id=? AND user_id=? AND target_type=? AND target_key=?
                """, rs -> rs.next() ? feedback(rs) : null, retrievalId, userId, request.targetType(), key);
    }

    public List<RagDtos.FeedbackView> pendingFeedback(int limit) {
        return jdbc.query("""
                SELECT id,retrieval_id,target_type,target_key,rating,reason,comment,review_status,
                  correct_source_urls::text,updated_at FROM rag_feedback
                WHERE review_status='PENDING' ORDER BY updated_at LIMIT ?
                """, (rs, row) -> feedback(rs), limit);
    }

    @Transactional
    public boolean review(UUID feedbackId, RagDtos.FeedbackReviewRequest request) {
        List<String> sources = request.correctSourceUrls() == null ? List.of() : request.correctSourceUrls().stream()
                .filter(value -> value != null && !value.isBlank()).distinct().toList();
        int changed = jdbc.update("""
                UPDATE rag_feedback SET review_status=?,correct_source_urls=?::jsonb,reviewed_by=?,
                  reviewed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?
                """, request.decision(), write(sources), request.reviewer(), feedbackId);
        if (changed == 0) return false;
        jdbc.update("UPDATE rag_dynamic_eval_case SET active=FALSE WHERE feedback_id=?", feedbackId);
        if ("APPROVED".equals(request.decision()) && !sources.isEmpty()) {
            jdbc.update("""
                    INSERT INTO rag_dynamic_eval_case(id,feedback_id,query_text,expected_source_urls,category,dataset_version)
                    SELECT ?,f.id,r.query_text,?::jsonb,coalesce(r.category,'uncategorized'),
                      coalesce((SELECT max(dataset_version)+1 FROM rag_dynamic_eval_case),1)
                    FROM rag_feedback f JOIN rag_retrieval r ON r.id=f.retrieval_id WHERE f.id=?
                    ON CONFLICT(feedback_id) DO UPDATE SET query_text=EXCLUDED.query_text,
                      expected_source_urls=EXCLUDED.expected_source_urls,category=EXCLUDED.category,
                      dataset_version=EXCLUDED.dataset_version,active=TRUE,created_at=CURRENT_TIMESTAMP
                    """, UUID.randomUUID(), write(sources), feedbackId);
        }
        return true;
    }

    public RagDtos.FeedbackSummary summary() {
        return jdbc.query("""
                SELECT count(*),count(*) FILTER(WHERE rating='HELPFUL'),
                  count(*) FILTER(WHERE rating='NOT_HELPFUL'),count(*) FILTER(WHERE review_status='PENDING'),
                  (SELECT count(*) FROM rag_dynamic_eval_case WHERE active=TRUE) FROM rag_feedback
                """, rs -> rs.next() ? new RagDtos.FeedbackSummary(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), rs.getLong(5)) : new RagDtos.FeedbackSummary(0, 0, 0, 0, 0));
    }

    public List<DynamicEvalCase> dynamicCases() {
        return jdbc.query("""
                SELECT id,query_text,expected_source_urls::text,category,dataset_version
                FROM rag_dynamic_eval_case WHERE active=TRUE ORDER BY dataset_version,id
                """, (rs, row) -> new DynamicEvalCase(rs.getObject(1, UUID.class), rs.getString(2),
                readList(rs.getString(3)), rs.getString(4), rs.getLong(5)));
    }

    public List<RagDtos.RevisionView> revisions(UUID documentId) {
        return jdbc.query("""
                SELECT id,document_id,version,title,category,source_url,publisher,trust_level,effective_from,expires_at,created_at
                FROM knowledge_document_revision WHERE document_id=? ORDER BY version DESC
                """, (rs, row) -> new RagDtos.RevisionView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                timestamp(rs, 9), timestamp(rs, 10), timestamp(rs, 11)), documentId);
    }

    public RevisionData revision(UUID documentId, int version) {
        return jdbc.query("""
                SELECT d.external_id,r.title,r.category,r.source_url,r.source_license,r.format,r.raw_content,
                  r.metadata::text,r.publisher,r.trust_level,r.effective_from,r.expires_at
                FROM knowledge_document_revision r JOIN knowledge_document d ON d.id=r.document_id
                WHERE r.document_id=? AND r.version=?
                """, rs -> rs.next() ? new RevisionData(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), readMap(rs.getString(8)),
                rs.getString(9), rs.getString(10), timestamp(rs, 11), timestamp(rs, 12)) : null,
                documentId, version);
    }

    public List<DeletionTask> claimDeletionTasks(int limit) {
        return jdbc.query("""
                UPDATE rag_deletion_task SET status='RUNNING',attempt_count=attempt_count+1,updated_at=CURRENT_TIMESTAMP
                WHERE id IN (SELECT id FROM rag_deletion_task WHERE status IN ('PENDING','FAILED')
                  AND next_attempt_at<=CURRENT_TIMESTAMP ORDER BY next_attempt_at FOR UPDATE SKIP LOCKED LIMIT ?)
                RETURNING id,document_id,attempt_count
                """, (rs, row) -> new DeletionTask(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3)), limit);
    }

    public void deletionSucceeded(UUID taskId, UUID documentId) {
        int deleted = jdbc.update("""
                UPDATE knowledge_document SET lifecycle_status='DELETED',raw_content='',metadata='{}'::jsonb,
                  index_status='INDEXED',index_error=NULL,indexed_at=CURRENT_TIMESTAMP,
                  deleted_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND lifecycle_status='DELETE_PENDING'
                """, documentId);
        if (deleted == 0) jdbc.update("""
                UPDATE knowledge_document SET index_status='PENDING',updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND lifecycle_status='ACTIVE'
                """, documentId);
        jdbc.update("UPDATE rag_deletion_task SET status='SUCCEEDED',last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?", taskId);
    }

    public void deletionFailed(UUID taskId, Throwable failure, int attempts) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        jdbc.update("""
                UPDATE rag_deletion_task SET status='FAILED',last_error=?,next_attempt_at=CURRENT_TIMESTAMP+(?*INTERVAL '30 seconds'),
                  updated_at=CURRENT_TIMESTAMP WHERE id=?
                """, message.substring(0, Math.min(1000, message.length())), Math.min(attempts, 20), taskId);
    }

    public RagDtos.DeleteStatus deleteStatus(UUID documentId) {
        return jdbc.query("""
                SELECT d.id,d.lifecycle_status,t.status,t.attempt_count,t.last_error,t.updated_at
                FROM knowledge_document d LEFT JOIN rag_deletion_task t ON t.document_id=d.id WHERE d.id=?
                """, rs -> rs.next() ? new RagDtos.DeleteStatus(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), rs.getInt(4), rs.getString(5), timestamp(rs, 6)) : null, documentId);
    }

    private RagDtos.FeedbackView feedback(ResultSet rs) throws SQLException {
        return new RagDtos.FeedbackView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                readList(rs.getString(9)), timestamp(rs, 10));
    }
    private LocalDateTime timestamp(ResultSet rs, int column) throws SQLException {
        return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toLocalDateTime();
    }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException(e); } }
    private List<String> readList(String value) { try { return json.readValue(value, new TypeReference<>() {}); } catch (Exception e) { throw new IllegalStateException(e); } }
    private Map<String, String> readMap(String value) { try { return json.readValue(value, new TypeReference<>() {}); } catch (Exception e) { throw new IllegalStateException(e); } }

    public record DynamicEvalCase(UUID id, String query, List<String> expectedSources, String category, long version) {}
    public record RevisionData(String externalId, String title, String category, String sourceUrl, String sourceLicense,
                               String format, String content, Map<String, String> metadata, String publisher,
                               String trustLevel, LocalDateTime effectiveFrom, LocalDateTime expiresAt) {}
    public record DeletionTask(UUID id, UUID documentId, int attempts) {}
}
