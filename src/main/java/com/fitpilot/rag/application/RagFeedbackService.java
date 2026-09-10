package com.fitpilot.rag.application;

import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.rag.dto.RagDtos;
import com.fitpilot.rag.infrastructure.RagGovernanceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Service
public class RagFeedbackService {
    private final RagGovernanceRepository repository;

    public RagFeedbackService(RagGovernanceRepository repository) { this.repository = repository; }

    public RagDtos.FeedbackView submit(long userId, UUID retrievalId, RagDtos.FeedbackRequest request) {
        if (!repository.ownsRetrieval(retrievalId, userId)) throw notFound();
        String targetKey = request.targetKey() == null ? "" : request.targetKey().trim();
        if ("CITATION".equals(request.targetType()) && (targetKey.isBlank()
                || !repository.containsCitation(retrievalId, targetKey))) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "citation is not part of this retrieval",
                    HttpStatus.BAD_REQUEST);
        }
        if ("NOT_HELPFUL".equals(request.rating()) && request.reason() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "reason is required for negative feedback",
                    HttpStatus.BAD_REQUEST);
        }
        return repository.upsertFeedback(retrievalId, userId, request);
    }

    public List<RagDtos.FeedbackView> pending(int limit) {
        return repository.pendingFeedback(Math.max(1, Math.min(limit, 100)));
    }

    public void review(UUID id, RagDtos.FeedbackReviewRequest request) {
        if ("APPROVED".equals(request.decision())
                && (request.correctSourceUrls() == null || request.correctSourceUrls().isEmpty())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "approved feedback requires correct sources",
                    HttpStatus.BAD_REQUEST);
        }
        if (!repository.review(id, request)) throw notFound();
    }

    public RagDtos.FeedbackSummary summary() { return repository.summary(); }

    public List<DynamicEvaluationCase> dynamicEvaluationCases() {
        return repository.dynamicCases().stream()
                .map(item -> new DynamicEvaluationCase(item.id(), item.query(), item.expectedSources(),
                        item.category(), item.version()))
                .toList();
    }

    public List<EvaluationDocumentRef> evaluationCorpus(List<DynamicEvaluationCase> cases, int limit) {
        List<String> categories = cases.stream().map(DynamicEvaluationCase::category)
                .filter(value -> value != null && !value.isBlank())
                .map(String::toLowerCase).distinct().toList();
        List<String> expectedSources = cases.stream().flatMap(item -> item.expectedSources().stream())
                .filter(value -> value != null && !value.isBlank()).distinct().toList();
        if (categories.isEmpty() || expectedSources.isEmpty()) return List.of();
        return repository.evaluationCorpus(categories, expectedSources, Math.max(1, Math.min(limit, 1000)))
                .stream().map(item -> new EvaluationDocumentRef(item.documentId(), item.version(),
                        item.sourceUrl(), item.category())).toList();
    }

    public RagDtos.IngestDocumentRequest evaluationDocument(EvaluationDocumentRef reference) {
        RagGovernanceRepository.RevisionData revision =
                repository.revision(reference.documentId(), reference.version());
        if (revision == null) throw new IllegalStateException("evaluation document revision is unavailable");
        return new RagDtos.IngestDocumentRequest(revision.externalId(), revision.title(), revision.category(),
                revision.sourceUrl(), revision.sourceLicense(), revision.format(), revision.content(),
                revision.metadata(), revision.publisher(), revision.trustLevel(),
                revision.effectiveFrom(), revision.expiresAt());
    }

    public Set<String> missingExpectedSources(List<DynamicEvaluationCase> cases,
                                              List<EvaluationDocumentRef> corpus) {
        Set<String> missing = new LinkedHashSet<>();
        cases.forEach(item -> missing.addAll(item.expectedSources()));
        corpus.forEach(item -> missing.remove(item.sourceUrl()));
        return Set.copyOf(missing);
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.RAG_FEEDBACK_NOT_FOUND, "retrieval or feedback not found", HttpStatus.NOT_FOUND);
    }

    public record DynamicEvaluationCase(UUID id, String query, List<String> expectedSources,
                                        String category, long version) {}
    public record EvaluationDocumentRef(UUID documentId, int version, String sourceUrl, String category) {}
}
