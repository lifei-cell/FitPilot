package com.fitpilot.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RagDtos {
    private RagDtos() {}

    public record IngestDocumentRequest(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9._:-]{3,160}") String externalId,
            @NotBlank @Size(max = 300) String title,
            @NotBlank @Size(max = 80) String category,
            @NotBlank @Size(max = 1000) String sourceUrl,
            @NotBlank @Size(max = 120) String sourceLicense,
            @NotBlank @Pattern(regexp = "(?i)MARKDOWN|TEXT") String format,
            @NotBlank @Size(max = 1_000_000) String content,
            Map<String, String> metadata) {}

    public record DocumentView(
            UUID id, String externalId, String title, String category, String sourceUrl,
            String sourceLicense, String format, String indexStatus, String indexError,
            int version, int parentChunks, int childChunks, LocalDateTime updatedAt, LocalDateTime indexedAt) {}

    public record SearchResponse(
            String query, String retrievalMode, String embeddingProvider, int candidateCount,
            List<RetrievedContext> contexts) {}

    public record RetrievedContext(
            UUID documentId, UUID chunkId, String title, String category, String heading,
            String content, double score, Citation citation, List<String> matchedBy) {}

    public record Citation(String sourceUrl, String sourceLicense) {}

}
