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
            Map<String, String> metadata,
            @Size(max = 200) String publisher,
            @Pattern(regexp = "OFFICIAL|INTERNAL|PROFESSIONAL|COMMUNITY") String trustLevel,
            LocalDateTime effectiveFrom,
            LocalDateTime expiresAt) {}

    public record DocumentView(
            UUID id, String externalId, String title, String category, String sourceUrl,
            String sourceLicense, String format, String indexStatus, String indexError,
            int version, String publisher, String trustLevel, String lifecycleStatus,
            LocalDateTime effectiveFrom, LocalDateTime expiresAt, int parentChunks, int childChunks,
            LocalDateTime updatedAt, LocalDateTime indexedAt) {}

    public record SearchResponse(
            UUID retrievalId, String query, String retrievalMode, String embeddingProvider, int candidateCount,
            List<RetrievedContext> contexts) {}

    public record RetrievedContext(
            UUID documentId, UUID chunkId, String title, String category, String heading,
            String content, double score, Citation citation, List<String> matchedBy) {}

    public record Citation(UUID documentId, String sourceUrl, String sourceLicense, String publisher, String trustLevel,
                           int documentVersion, LocalDateTime expiresAt) {}

    public record RevisionView(UUID id, UUID documentId, int version, String title, String category,
                               String sourceUrl, String publisher, String trustLevel, LocalDateTime effectiveFrom,
                               LocalDateTime expiresAt, LocalDateTime createdAt) {}
    public record DeleteStatus(UUID documentId, String lifecycleStatus, String taskStatus, int attempts,
                               String lastError, LocalDateTime updatedAt) {}
    public record FeedbackRequest(
            @NotBlank @Pattern(regexp = "ANSWER|CITATION") String targetType,
            @Size(max = 80) String targetKey,
            @NotBlank @Pattern(regexp = "HELPFUL|NOT_HELPFUL") String rating,
            @Pattern(regexp = "IRRELEVANT|OUTDATED|WRONG_CITATION|UNSAFE|OTHER") String reason,
            @Size(max = 1000) String comment) {}
    public record FeedbackView(UUID id, UUID retrievalId, String targetType, String targetKey, String rating,
                               String reason, String comment, String reviewStatus, List<String> correctSourceUrls,
                               LocalDateTime updatedAt) {}
    public record FeedbackReviewRequest(
            @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
            List<@Size(max = 1000) String> correctSourceUrls,
            @Size(max = 120) String reviewer) {}
    public record FeedbackSummary(long total, long helpful, long notHelpful, long pendingReview,
                                  long approvedForEvaluation) {}

}
