package com.fitpilot.evaluation.application;

import com.fitpilot.evaluation.infrastructure.EvaluationRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvaluationReportServiceTest {
    @Test
    void failsRagGateWhenCategoryMetricsRegressBeyondFivePoints() {
        EvaluationRepository repository = mock(EvaluationRepository.class);
        EvaluationReportService reports = new EvaluationReportService(repository);
        UUID runId = UUID.randomUUID();
        when(repository.lastSuccessfulRagMetrics(runId)).thenReturn(Map.of(
                "category.strength.recallAt5", .90,
                "category.strength.mrr", .90));
        EvaluationMetricCalculator.RagAccumulator metrics = new EvaluationMetricCalculator.RagAccumulator();
        metrics.add("strength", new EvaluationMetricCalculator.RagCaseMetrics(
                List.of("source"), .8, .8, .8, .8, .8, true));

        reports.finishRag(runId, "worker", 1, metrics);

        verify(repository).failRagGate(eq(runId), eq("worker"), eq(1), eq(0),
                anyMap(), contains("regressed more than 5pp"));
        verify(repository, never()).finishRag(eq(runId), eq("worker"), eq(1), eq(0), anyMap());
    }
}
