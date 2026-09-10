package com.fitpilot.evaluation.application;

import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.evaluation.config.EvaluationProperties;
import com.fitpilot.evaluation.domain.EvaluationCases;
import com.fitpilot.evaluation.dto.EvaluationDtos;
import com.fitpilot.evaluation.infrastructure.EvaluationRepository;
import com.fitpilot.llm.application.PromptRegistry;
import com.fitpilot.rag.application.RagFeedbackService;
import com.fitpilot.rag.application.RagExperimentCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Public evaluation facade; execution, scheduling, metrics and reporting live in dedicated collaborators. */
@Service
public class EvaluationService {
    private final EvaluationDatasetLoader datasets;
    private final EvaluationRepository repository;
    private final PromptRegistry prompts;
    private final RagFeedbackService ragFeedback;
    private final EvaluationTaskScheduler scheduler;
    private final EvaluationProperties properties;
    private final RagExperimentCatalog experimentCatalog;

    public EvaluationService(EvaluationDatasetLoader datasets, EvaluationRepository repository,
                             PromptRegistry prompts, RagFeedbackService ragFeedback,
                             EvaluationTaskScheduler scheduler, EvaluationProperties properties,
                             RagExperimentCatalog experimentCatalog) {
        this.datasets = datasets;
        this.repository = repository;
        this.prompts = prompts;
        this.ragFeedback = ragFeedback;
        this.scheduler = scheduler;
        this.properties = properties;
        this.experimentCatalog = experimentCatalog;
    }

    public UUID startAgent(String requestedMode) {
        String mode = requestedMode == null || requestedMode.isBlank() ? "RULE_WORKFLOW" : requestedMode;
        UUID id = UUID.randomUUID();
        repository.createAgent(id, EvaluationDatasetLoader.AGENT_VERSION, mode, mode,
                prompts.version(), properties.getRunTimeoutSeconds());
        scheduler.dispatch(new EvaluationRepository.PendingRun(
                id, EvaluationRepository.RunType.AGENT, mode, List.of()));
        return id;
    }

    public UUID startRag() {
        UUID id = UUID.randomUUID();
        List<RagFeedbackService.DynamicEvaluationCase> dynamic =
                List.copyOf(ragFeedback.dynamicEvaluationCases());
        List<EvaluationCases.RagCase> frozen = new ArrayList<>(datasets.rag());
        dynamic.forEach(item -> frozen.add(new EvaluationCases.RagCase(
                "dynamic-" + item.id(), item.query(), List.of(),
                item.expectedSources(), item.category())));
        long version = dynamic.stream()
                .mapToLong(RagFeedbackService.DynamicEvaluationCase::version)
                .max().orElse(0);
        String dataset = EvaluationDatasetLoader.RAG_VERSION + "+dynamic-" + version;
        List<EvaluationCases.RagCase> cases = List.copyOf(frozen);
        repository.createRag(id, dataset, Map.of(
                "staticVersion", EvaluationDatasetLoader.RAG_VERSION,
                "dynamicVersion", version,
                "staticCases", datasets.rag().size(),
                "dynamicCases", dynamic.size()), cases, properties.getRunTimeoutSeconds());
        scheduler.dispatch(new EvaluationRepository.PendingRun(
                id, EvaluationRepository.RunType.RAG, "", cases));
        return id;
    }

    public UUID startRagFeedbackExperiment() {
        List<RagFeedbackService.DynamicEvaluationCase> feedbackCases =
                List.copyOf(ragFeedback.dynamicEvaluationCases());
        if (feedbackCases.isEmpty()) {
            throw validation("no approved negative RAG feedback is available for offline evaluation");
        }
        List<RagFeedbackService.EvaluationDocumentRef> corpus = ragFeedback.evaluationCorpus(
                feedbackCases, properties.getRagExperimentMaxDocuments());
        int missingSources = ragFeedback.missingExpectedSources(feedbackCases, corpus).size();
        if (missingSources > 0) {
            throw validation(missingSources + " reviewed source documents are unavailable for offline evaluation");
        }

        List<EvaluationCases.RagCase> cases = feedbackCases.stream()
                .map(item -> new EvaluationCases.RagCase("feedback-" + item.id(), item.query(), List.of(),
                        item.expectedSources(), item.category()))
                .toList();
        var profiles = experimentCatalog.profiles();
        List<EvaluationCases.RagDocumentRef> documents = corpus.stream()
                .map(item -> new EvaluationCases.RagDocumentRef(item.documentId(), item.version(),
                        item.sourceUrl(), item.category()))
                .toList();
        var experiment = new EvaluationCases.RagExperiment(profiles, documents);
        long feedbackVersion = feedbackCases.stream()
                .mapToLong(RagFeedbackService.DynamicEvaluationCase::version).max().orElseThrow();
        UUID id = UUID.randomUUID();
        repository.createRagExperiment(id, "rag-feedback-v" + feedbackVersion, Map.of(
                "source", "APPROVED_NEGATIVE_FEEDBACK",
                "feedbackVersion", feedbackVersion,
                "cases", cases.size(),
                "documents", documents.size(),
                "profiles", profiles), cases, experiment, properties.getRunTimeoutSeconds());
        scheduler.dispatch(new EvaluationRepository.PendingRun(
                id, EvaluationRepository.RunType.RAG, "FEEDBACK_EXPERIMENT", cases, experiment));
        return id;
    }

    public Optional<EvaluationDtos.RunView> find(UUID id) {
        return repository.find(id);
    }

    public void recover() {
        scheduler.recover();
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, HttpStatus.BAD_REQUEST);
    }
}
