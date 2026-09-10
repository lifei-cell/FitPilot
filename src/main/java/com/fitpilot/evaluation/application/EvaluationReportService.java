package com.fitpilot.evaluation.application;

import com.fitpilot.evaluation.infrastructure.EvaluationRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EvaluationReportService {
    private final EvaluationRepository repository;

    public EvaluationReportService(EvaluationRepository repository) {
        this.repository = repository;
    }

    public void finishAgent(UUID runId, String workerId, int total,
                            EvaluationMetricCalculator.AgentAccumulator metrics, String model) {
        repository.finishAgent(runId, workerId, total, metrics.passed(), metrics.report(total), model);
    }

    public void finishRag(UUID runId, String workerId, int total,
                          EvaluationMetricCalculator.RagAccumulator metrics) {
        Map<String, Double> report = metrics.report(total);
        Map<String, Double> baseline = repository.lastSuccessfulRagMetrics(runId);
        List<String> failures = regressionFailures(report, baseline);
        if (failures.isEmpty()) {
            repository.finishRag(runId, workerId, total, metrics.passed(), report);
        } else {
            repository.failRagGate(runId, workerId, total, metrics.passed(), report,
                    String.join("; ", failures));
        }
    }

    private List<String> regressionFailures(Map<String, Double> report, Map<String, Double> baseline) {
        List<String> failures = new ArrayList<>();
        if (report.get("citationValidity") < 1) failures.add("Citation Validity must be 100%");
        report.forEach((key, value) -> {
            boolean categoryMetric = key.startsWith("category.")
                    && (key.endsWith(".recallAt5") || key.endsWith(".mrr"));
            if (categoryMetric && baseline.containsKey(key) && baseline.get(key) - value > .05) {
                failures.add(key + " regressed more than 5pp");
            }
        });
        return failures;
    }
}
