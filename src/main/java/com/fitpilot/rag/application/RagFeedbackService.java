package com.fitpilot.rag.application;

import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.rag.dto.RagDtos;
import com.fitpilot.rag.infrastructure.RagGovernanceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
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

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.RAG_FEEDBACK_NOT_FOUND, "retrieval or feedback not found", HttpStatus.NOT_FOUND);
    }
}
