package com.fitpilot.evaluation.application;

import com.fitpilot.evaluation.config.EvaluationProperties;
import com.fitpilot.evaluation.domain.EvaluationCases;
import com.fitpilot.evaluation.dto.EvaluationDtos;
import com.fitpilot.evaluation.infrastructure.EvaluationRepository;
import com.fitpilot.llm.application.PromptRegistry;
import com.fitpilot.rag.application.RagFeedbackService;
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

    public EvaluationService(EvaluationDatasetLoader datasets, EvaluationRepository repository,
                             PromptRegistry prompts, RagFeedbackService ragFeedback,
                             EvaluationTaskScheduler scheduler, EvaluationProperties properties) {
        this.datasets = datasets;
        this.repository = repository;
        this.prompts = prompts;
        this.ragFeedback = ragFeedback;
        this.scheduler = scheduler;
        this.properties = properties;
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

    public Optional<EvaluationDtos.RunView> find(UUID id) {
        return repository.find(id);
    }

    public void recover() {
        scheduler.recover();
    }
}
