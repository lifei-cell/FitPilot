package com.fitpilot.rag.application;

import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.rag.dto.RagDtos;
import com.fitpilot.rag.infrastructure.RagGovernanceRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RagFeedbackServiceTest {
    private final RagGovernanceRepository repository = mock(RagGovernanceRepository.class);
    private final RagFeedbackService service = new RagFeedbackService(repository);

    @Test
    void rejectsCrossUserRetrievalFeedback() {
        UUID retrievalId = UUID.randomUUID();
        when(repository.ownsRetrieval(retrievalId, 7)).thenReturn(false);
        var request = new RagDtos.FeedbackRequest("ANSWER", null, "HELPFUL", null, null);

        assertThatThrownBy(() -> service.submit(7, retrievalId, request)).isInstanceOf(BusinessException.class);
        verify(repository, never()).upsertFeedback(any(), anyLong(), any());
    }

    @Test
    void rejectsCitationThatWasNotReturnedByRetrieval() {
        UUID retrievalId = UUID.randomUUID();
        when(repository.ownsRetrieval(retrievalId, 7)).thenReturn(true);
        when(repository.containsCitation(retrievalId, "unknown-document")).thenReturn(false);
        var request = new RagDtos.FeedbackRequest("CITATION", "unknown-document", "NOT_HELPFUL",
                "WRONG_CITATION", null);

        assertThatThrownBy(() -> service.submit(7, retrievalId, request)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("citation is not part");
    }

    @Test
    void approvedFeedbackRequiresReviewedCorrectSource() {
        var request = new RagDtos.FeedbackReviewRequest("APPROVED", java.util.List.of(), "operator");
        assertThatThrownBy(() -> service.review(UUID.randomUUID(), request)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("correct sources");
    }
}
