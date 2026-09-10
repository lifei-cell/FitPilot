package com.fitpilot.evaluation.application;

import com.fitpilot.agent.application.AgentPlanner;
import com.fitpilot.evaluation.infrastructure.EvaluationRepository;
import com.fitpilot.llm.application.LlmGateway;
import com.fitpilot.llm.application.PromptRegistry;
import com.fitpilot.llm.domain.LlmModels;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class AgentEvaluationRunner {
    private final EvaluationDatasetLoader datasets;
    private final EvaluationRepository repository;
    private final AgentPlanner planner;
    private final LlmGateway llm;
    private final PromptRegistry prompts;
    private final EvaluationMetricCalculator metrics;
    private final EvaluationReportService reports;

    public AgentEvaluationRunner(EvaluationDatasetLoader datasets, EvaluationRepository repository,
                                 AgentPlanner planner, LlmGateway llm, PromptRegistry prompts,
                                 EvaluationMetricCalculator metrics, EvaluationReportService reports) {
        this.datasets = datasets;
        this.repository = repository;
        this.planner = planner;
        this.llm = llm;
        this.prompts = prompts;
        this.metrics = metrics;
        this.reports = reports;
    }

    public void run(UUID runId, String mode, String workerId, Runnable ensureLease) {
        List<com.fitpilot.evaluation.domain.EvaluationCases.AgentCase> cases = datasets.agent();
        EvaluationMetricCalculator.AgentAccumulator accumulator =
                new EvaluationMetricCalculator.AgentAccumulator();
        String model = mode;
        for (var item : cases) {
            ensureLease.run();
            long started = System.nanoTime();
            LlmModels.WorkflowDecision fallback = planner.decide(item.query());
            LlmModels.Result<LlmModels.WorkflowDecision> result = "ACTIVE_MODEL".equals(mode)
                    ? llm.decide(null, item.query(), fallback)
                    : LlmModels.Result.rule(fallback, prompts.version());
            List<String> actual = result.value().tools();
            boolean selected = actual.equals(item.expectedTools());
            boolean violation = actual.stream().anyMatch(item.forbiddenTools()::contains);
            boolean hallucination = actual.stream().anyMatch(tool -> !LlmGateway.READ_TOOLS.contains(tool)
                    && !"create_training_plan".equals(tool));
            boolean taskSucceeded = selected && !violation && !hallucination;
            accumulator.add(selected, taskSucceeded, violation, hallucination);
            model = result.model();
            repository.agentResult(runId, item.id(), metrics.hash(item.query()), item.expectedTools(), actual,
                    selected, taskSucceeded, violation, hallucination, metrics.elapsed(started));
        }
        reports.finishAgent(runId, workerId, cases.size(), accumulator, model);
    }
}
