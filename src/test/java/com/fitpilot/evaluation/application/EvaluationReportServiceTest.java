package com.fitpilot.evaluation.application;

import com.fitpilot.evaluation.infrastructure.EvaluationRepository;
import com.fitpilot.rag.domain.RagTuningProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
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

    @Test
    void writesComparableFeedbackExperimentReportAndRecommendsEligibleWinner() {
        EvaluationRepository repository = mock(EvaluationRepository.class);
        EvaluationReportService reports = new EvaluationReportService(repository);
        EvaluationMetricCalculator.RagAccumulator baseline = new EvaluationMetricCalculator.RagAccumulator();
        EvaluationMetricCalculator.RagAccumulator better = new EvaluationMetricCalculator.RagAccumulator();
        baseline.add("strength", metric(0, 0, true));
        better.add("strength", metric(1, 1, true));
        var profiles = List.of(profile("baseline"), profile("better"));
        UUID runId = UUID.randomUUID();

        reports.finishRagExperiment(runId, "worker", 1, profiles,
                Map.of("baseline", baseline, "better", better));

        verify(repository).finishRagExperiment(eq(runId), eq("worker"), eq(2), eq(1),
                argThat(metrics -> metrics.get("profile.better.recallAt5") == 1d
                        && metrics.containsKey("profile.better.category.strength.citationValidity")),
                argThat(report -> "better".equals(report.get("recommendedProfile"))));
    }

    private EvaluationMetricCalculator.RagCaseMetrics metric(double recall, double mrr,
                                                              boolean citationValid) {
        return new EvaluationMetricCalculator.RagCaseMetrics(
                List.of(), recall, mrr, recall, recall, recall, citationValid);
    }

    private RagTuningProfile profile(String id) {
        return new RagTuningProfile(id, 2400, 700, 100, 60, 1, 1,
                "TERM_OVERLAP_TRUST");
    }
}
