package com.fitpilot.evaluation.application;

import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.evaluation.config.EvaluationProperties;
import com.fitpilot.evaluation.infrastructure.EvaluationRepository;
import com.fitpilot.llm.application.PromptRegistry;
import com.fitpilot.rag.application.RagFeedbackService;
import com.fitpilot.rag.application.RagExperimentCatalog;
import com.fitpilot.rag.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EvaluationServiceFeedbackExperimentTest {
    private final EvaluationRepository repository = mock(EvaluationRepository.class);
    private final RagFeedbackService feedback = mock(RagFeedbackService.class);
    private final EvaluationTaskScheduler scheduler = mock(EvaluationTaskScheduler.class);
    private final EvaluationProperties properties = new EvaluationProperties();
    private final EvaluationService service = new EvaluationService(mock(EvaluationDatasetLoader.class), repository,
            mock(PromptRegistry.class), feedback, scheduler, properties,
            new RagExperimentCatalog(new RagProperties()));

    @Test
    void freezesApprovedNegativeCasesDocumentsAndExperimentProfilesBeforeDispatch() {
        UUID caseId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        var evaluationCase = new RagFeedbackService.DynamicEvaluationCase(caseId, "RPE 8 是什么",
                List.of("https://example.org/rpe"), "training", 12);
        var document = new RagFeedbackService.EvaluationDocumentRef(documentId, 3,
                "https://example.org/rpe", "training");
        when(feedback.dynamicEvaluationCases()).thenReturn(List.of(evaluationCase));
        when(feedback.evaluationCorpus(anyList(), eq(200))).thenReturn(List.of(document));
        when(feedback.missingExpectedSources(anyList(), anyList())).thenReturn(Set.of());

        UUID runId = service.startRagFeedbackExperiment();

        verify(repository).createRagExperiment(eq(runId), eq("rag-feedback-v12"), anyMap(),
                argThat(cases -> cases.size() == 1 && cases.getFirst().id().equals("feedback-" + caseId)),
                argThat(experiment -> experiment.profiles().size() == 7
                        && experiment.documents().getFirst().version() == 3), eq(600));
        verify(scheduler).dispatch(argThat(run -> run.id().equals(runId)
                && run.ragExperiment().profiles().size() == 7));
    }

    @Test
    void refusesExperimentWithoutReviewedNegativeFeedback() {
        when(feedback.dynamicEvaluationCases()).thenReturn(List.of());

        assertThatThrownBy(service::startRagFeedbackExperiment)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("approved negative");
        verifyNoInteractions(repository, scheduler);
    }
}
