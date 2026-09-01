package com.fitpilot.rag.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.rag.domain.KnowledgeModels;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;

@Repository
public class KnowledgeRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper json;

    public KnowledgeRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.json = json;
    }

    @Transactional
    public UUID replace(DocumentWrite document, List<KnowledgeModels.StoredChunk> chunks) {
        Existing existing = jdbc.query("SELECT id, version FROM knowledge_document WHERE external_id=? FOR UPDATE",
                rs -> rs.next() ? new Existing(rs.getObject(1, UUID.class), rs.getInt(2)) : null,
                document.externalId());
        UUID id = existing == null ? document.id() : existing.id();
        String metadata = writeJson(document.metadata());
        if (existing == null) {
            jdbc.update("""
                    INSERT INTO knowledge_document(id, external_id, title, category, source_url, source_license,
                      format, content_hash, raw_content, metadata, publisher, trust_level, effective_from, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                    """, id, document.externalId(), document.title(), document.category(), document.sourceUrl(),
                    document.sourceLicense(), document.format(), document.contentHash(), document.rawContent(), metadata,
                    document.publisher(), document.trustLevel(), document.effectiveFrom(), document.expiresAt());
        } else {
            jdbc.update("DELETE FROM rag_deletion_task WHERE document_id=? AND status IN ('PENDING','FAILED','SUCCEEDED')", id);
            jdbc.update("DELETE FROM knowledge_chunk WHERE document_id=?", id);
            jdbc.update("""
                    UPDATE knowledge_document SET title=?, category=?, source_url=?, source_license=?, format=?,
                      content_hash=?, raw_content=?, metadata=?::jsonb, index_status='PENDING', index_error=NULL,
                      indexed_at=NULL, version=?, publisher=?, trust_level=?, effective_from=?, expires_at=?,
                      lifecycle_status='ACTIVE', deleted_at=NULL, updated_at=CURRENT_TIMESTAMP WHERE id=?
                    """, document.title(), document.category(), document.sourceUrl(), document.sourceLicense(),
                    document.format(), document.contentHash(), document.rawContent(), metadata, existing.version() + 1,
                    document.publisher(), document.trustLevel(), document.effectiveFrom(), document.expiresAt(), id);
        }
        int version = existing == null ? 1 : existing.version() + 1;
        jdbc.update("""
                INSERT INTO knowledge_document_revision(id,document_id,version,title,category,source_url,source_license,
                  publisher,trust_level,effective_from,expires_at,format,content_hash,raw_content,metadata)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
                """, UUID.randomUUID(), id, version, document.title(), document.category(), document.sourceUrl(),
                document.sourceLicense(), document.publisher(), document.trustLevel(), document.effectiveFrom(),
                document.expiresAt(), document.format(), document.contentHash(), document.rawContent(), metadata);
        for (KnowledgeModels.StoredChunk chunk : chunks) insertChunk(id, chunk);
        return id;
    }

    private void insertChunk(UUID documentId, KnowledgeModels.StoredChunk chunk) {
        jdbc.update("""
                INSERT INTO knowledge_chunk(id, document_id, parent_chunk_id, chunk_type, ordinal, heading,
                  content, lexical_text, embedding, char_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::vector, ?)
                """, chunk.id(), documentId, chunk.parentChunkId(), chunk.chunkType(), chunk.ordinal(),
                chunk.heading(), chunk.content(), chunk.lexicalText(),
                chunk.embedding() == null ? null : vectorLiteral(chunk.embedding()), chunk.content().length());
    }

    public Optional<KnowledgeModels.Document> findDocument(UUID id) {
        return jdbc.query("SELECT * FROM knowledge_document WHERE id=?", this::singleDocument, id);
    }

    public List<KnowledgeModels.Document> listDocuments(int limit) {
        return jdbc.query("SELECT * FROM knowledge_document ORDER BY updated_at DESC LIMIT ?",
                (rs, row) -> mapDocument(rs), limit);
    }

    public int countChunks(UUID documentId, String type) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_chunk WHERE document_id=? AND chunk_type=?",
                Integer.class, documentId, type);
        return count == null ? 0 : count;
    }

    public List<UUID> retryCandidates(int limit) {
        return jdbc.query("""
                SELECT id FROM knowledge_document
                WHERE index_status IN ('PENDING', 'FAILED')
                  AND lifecycle_status='ACTIVE' AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)
                   OR (index_status='INDEXING' AND updated_at < CURRENT_TIMESTAMP - INTERVAL '5 minutes')
                ORDER BY updated_at LIMIT ?
                """, (rs, row) -> rs.getObject(1, UUID.class), limit);
    }

    public boolean markIndexing(UUID id) {
        return jdbc.update("""
                UPDATE knowledge_document SET index_status='INDEXING', index_error=NULL, updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND lifecycle_status='ACTIVE' AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)
                  AND (index_status IN ('PENDING', 'FAILED', 'INDEXED')
                  OR (index_status='INDEXING' AND updated_at < CURRENT_TIMESTAMP - INTERVAL '5 minutes'))
                """, id) == 1;
    }

    public void markIndexed(UUID id) {
        jdbc.update("""
                UPDATE knowledge_document SET index_status='INDEXED', indexed_at=CURRENT_TIMESTAMP,
                  index_error=NULL, updated_at=CURRENT_TIMESTAMP WHERE id=?
                """, id);
    }

    public void markFailed(UUID id, Throwable failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        jdbc.update("""
                UPDATE knowledge_document SET index_status='FAILED', index_error=?, updated_at=CURRENT_TIMESTAMP
                WHERE id=?
                """, message.substring(0, Math.min(1000, message.length())), id);
    }

    public void resetPending(UUID id) {
        jdbc.update("""
                UPDATE knowledge_document SET index_status='PENDING', index_error=NULL, updated_at=CURRENT_TIMESTAMP
                WHERE id=?
                """, id);
    }

    public List<KnowledgeModels.IndexChunk> chunksForIndex(UUID documentId) {
        return jdbc.query("""
                SELECT c.id, c.document_id, d.title, d.category, c.heading, c.content, c.lexical_text,
                       d.source_url, d.source_license, d.publisher, d.trust_level, d.version, d.expires_at
                FROM knowledge_chunk c JOIN knowledge_document d ON d.id=c.document_id
                WHERE c.document_id=? AND c.chunk_type='CHILD' AND d.lifecycle_status='ACTIVE'
                  AND (d.expires_at IS NULL OR d.expires_at>CURRENT_TIMESTAMP) ORDER BY c.ordinal
                """, (rs, row) -> new KnowledgeModels.IndexChunk(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getString(10), rs.getString(11), rs.getInt(12),
                rs.getTimestamp(13) == null ? null : rs.getTimestamp(13).toLocalDateTime()), documentId);
    }

    public List<KnowledgeModels.SearchHit> vectorSearch(float[] embedding, String category, int limit) {
        String vector = vectorLiteral(embedding);
        if (category == null || category.isBlank()) {
            return jdbc.query("""
                    SELECT c.id, 1 - (c.embedding <=> ?::vector) AS score
                    FROM knowledge_chunk c JOIN knowledge_document d ON d.id=c.document_id
                    WHERE c.chunk_type='CHILD' AND c.embedding IS NOT NULL AND d.lifecycle_status='ACTIVE'
                      AND (d.effective_from IS NULL OR d.effective_from<=CURRENT_TIMESTAMP)
                      AND (d.expires_at IS NULL OR d.expires_at>CURRENT_TIMESTAMP)
                    ORDER BY c.embedding <=> ?::vector LIMIT ?
                    """, (rs, row) -> new KnowledgeModels.SearchHit(
                    rs.getObject(1, UUID.class), rs.getDouble(2)), vector, vector, limit);
        }
        return jdbc.query("""
                SELECT c.id, 1 - (c.embedding <=> ?::vector) AS score
                FROM knowledge_chunk c JOIN knowledge_document d ON d.id=c.document_id
                WHERE c.chunk_type='CHILD' AND c.embedding IS NOT NULL
                  AND LOWER(d.category)=LOWER(?)
                  AND d.lifecycle_status='ACTIVE'
                  AND (d.effective_from IS NULL OR d.effective_from<=CURRENT_TIMESTAMP)
                  AND (d.expires_at IS NULL OR d.expires_at>CURRENT_TIMESTAMP)
                ORDER BY c.embedding <=> ?::vector LIMIT ?
                """, (rs, row) -> new KnowledgeModels.SearchHit(rs.getObject(1, UUID.class), rs.getDouble(2)),
                vector, category, vector, limit);
    }

    public Map<UUID, KnowledgeModels.ChunkContext> contexts(List<UUID> chunkIds) {
        if (chunkIds.isEmpty()) return Map.of();
        List<KnowledgeModels.ChunkContext> rows = namedJdbc.query("""
                SELECT c.id, p.id, d.id, d.title, d.category, c.heading, c.content, p.content,
                       d.source_url, d.source_license, d.publisher, d.trust_level, d.version, d.expires_at
                FROM knowledge_chunk c
                JOIN knowledge_chunk p ON p.id=c.parent_chunk_id
                JOIN knowledge_document d ON d.id=c.document_id
                WHERE c.id IN (:ids)
                  AND d.lifecycle_status='ACTIVE'
                  AND (d.effective_from IS NULL OR d.effective_from<=CURRENT_TIMESTAMP)
                  AND (d.expires_at IS NULL OR d.expires_at>CURRENT_TIMESTAMP)
                """, Map.of("ids", chunkIds), (rs, row) -> new KnowledgeModels.ChunkContext(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getInt(13),
                rs.getTimestamp(14) == null ? null : rs.getTimestamp(14).toLocalDateTime()));
        Map<UUID, KnowledgeModels.ChunkContext> result = new LinkedHashMap<>();
        rows.forEach(row -> result.put(row.chunkId(), row));
        return result;
    }

    @Transactional
    public boolean requestDelete(UUID id) {
        int changed = jdbc.update("""
                UPDATE knowledge_document SET lifecycle_status='DELETE_PENDING',index_status='PENDING',updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND lifecycle_status NOT IN ('DELETE_PENDING','DELETED')
                """, id);
        if (changed == 0) return false;
        jdbc.update("DELETE FROM knowledge_chunk WHERE document_id=?", id);
        jdbc.update("""
                INSERT INTO rag_deletion_task(id,document_id) VALUES (?,?)
                ON CONFLICT(document_id) DO UPDATE SET status='PENDING',next_attempt_at=CURRENT_TIMESTAMP,
                  last_error=NULL,updated_at=CURRENT_TIMESTAMP
                """, UUID.randomUUID(), id);
        return true;
    }

    private Optional<KnowledgeModels.Document> singleDocument(ResultSet rs) throws SQLException {
        return rs.next() ? Optional.of(mapDocument(rs)) : Optional.empty();
    }

    private KnowledgeModels.Document mapDocument(ResultSet rs) throws SQLException {
        return new KnowledgeModels.Document(
                rs.getObject("id", UUID.class), rs.getString("external_id"), rs.getString("title"),
                rs.getString("category"), rs.getString("source_url"), rs.getString("source_license"),
                rs.getString("format"), rs.getString("content_hash"), rs.getString("raw_content"),
                readMetadata(rs.getString("metadata")), rs.getString("index_status"), rs.getString("index_error"),
                rs.getInt("version"), rs.getString("publisher"), rs.getString("trust_level"),
                lifecycle(rs), timestamp(rs, "effective_from"), timestamp(rs, "expires_at"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime(),
                rs.getTimestamp("indexed_at") == null ? null : rs.getTimestamp("indexed_at").toLocalDateTime());
    }

    private String writeJson(Map<String, String> metadata) {
        try { return json.writeValueAsString(metadata == null ? Map.of() : metadata); }
        catch (Exception exception) { throw new IllegalArgumentException("invalid document metadata", exception); }
    }

    private Map<String, String> readMetadata(String value) {
        try { return json.readValue(value, new TypeReference<>() {}); }
        catch (Exception exception) { throw new IllegalStateException("invalid stored metadata", exception); }
    }

    private String vectorLiteral(float[] vector) {
        StringBuilder value = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) value.append(',');
            value.append(Float.toString(vector[i]));
        }
        return value.append(']').toString();
    }

    public record DocumentWrite(
            UUID id, String externalId, String title, String category, String sourceUrl, String sourceLicense,
            String format, String contentHash, String rawContent, Map<String, String> metadata, String publisher,
            String trustLevel, LocalDateTime effectiveFrom, LocalDateTime expiresAt) {}
    private record Existing(UUID id, int version) {}

    private String lifecycle(ResultSet rs) throws SQLException {
        String status = rs.getString("lifecycle_status");
        LocalDateTime expiresAt = timestamp(rs, "expires_at");
        return "ACTIVE".equals(status) && expiresAt != null && !expiresAt.isAfter(LocalDateTime.now()) ? "EXPIRED" : status;
    }
    private LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toLocalDateTime();
    }
}
