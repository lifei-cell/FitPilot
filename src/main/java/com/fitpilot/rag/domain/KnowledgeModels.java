package com.fitpilot.rag.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class KnowledgeModels {
    private KnowledgeModels() {}

    public record Document(
            UUID id, String externalId, String title, String category, String sourceUrl, String sourceLicense,
            String format, String contentHash, String rawContent, Map<String, String> metadata,
            String indexStatus, String indexError, int version, String publisher, String trustLevel,
            String lifecycleStatus, LocalDateTime effectiveFrom, LocalDateTime expiresAt,
            LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime indexedAt) {}

    public record StoredChunk(
            UUID id, UUID parentChunkId, String chunkType, int ordinal,
            String heading, String content, String lexicalText, float[] embedding) {}

    public record SearchHit(UUID chunkId, double score) {}

    public record DocumentVersion(UUID id, int version) {
        public long indexingVersion() { return 2L * version; }
        public long deletionVersion() { return indexingVersion() + 1; }
    }

    public record IndexClaim(DocumentVersion document, UUID token, int slotCount, List<IndexChunk> chunks) {}

    public record IndexChunk(
            UUID chunkId, UUID documentId, String title, String category, String heading,
            String content, String lexicalText, String sourceUrl, String sourceLicense,
            String publisher, String trustLevel, int version, LocalDateTime expiresAt, String corpusScope) {}

    public record ChunkContext(
            UUID chunkId, UUID parentChunkId, UUID documentId, String title, String category,
            String heading, String childContent, String parentContent, String sourceUrl, String sourceLicense,
            String publisher, String trustLevel, int version, LocalDateTime expiresAt) {}
}
