package com.fitpilot.evaluation.application;

import com.fitpilot.evaluation.infrastructure.EvaluationRepository;
import com.fitpilot.rag.domain.RagTuningProfile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    public void finishRagExperiment(UUID runId, String workerId, int casesPerProfile,
                                    List<RagTuningProfile> profiles,
                                    Map<String, EvaluationMetricCalculator.RagAccumulator> accumulators) {
        EvaluationMetricCalculator.RagMetricSnapshot baseline =
                accumulators.get("baseline").overall(casesPerProfile);
        Map<String, Double> flatMetrics = new LinkedHashMap<>();
        List<Map<String, Object>> profileReports = new ArrayList<>();
        String recommended = "NONE";
        double bestScore = -1;
        int passed = 0;

        for (RagTuningProfile profile : profiles) {
            EvaluationMetricCalculator.RagAccumulator accumulator = accumulators.get(profile.id());
            EvaluationMetricCalculator.RagMetricSnapshot overall = accumulator.overall(casesPerProfile);
            accumulator.report(casesPerProfile).forEach((key, value) ->
                    flatMetrics.put("profile." + profile.id() + "." + key, value));
            double score = round(overall.recallAt5() * .5 + overall.mrr() * .3
                    + overall.citationValidity() * .2);
            boolean eligible = overall.citationValidity() == 1;
            if (eligible && score > bestScore) {
                recommended = profile.id();
                bestScore = score;
            }
            passed += accumulator.passed();
            profileReports.add(profileReport(profile, overall, baseline,
                    accumulator.categoryMetrics(), score, eligible));
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("source", "APPROVED_NEGATIVE_FEEDBACK");
        report.put("baselineProfile", "baseline");
        report.put("recommendedProfile", recommended);
        report.put("selectionRule", "citationValidity=1, then 0.5*Recall@5 + 0.3*MRR + 0.2*CitationValidity");
        report.put("profiles", profileReports);
        repository.finishRagExperiment(runId, workerId, casesPerProfile * profiles.size(), passed,
                flatMetrics, report);
    }

    private Map<String, Object> profileReport(RagTuningProfile profile,
                                              EvaluationMetricCalculator.RagMetricSnapshot overall,
                                              EvaluationMetricCalculator.RagMetricSnapshot baseline,
                                              Map<String, EvaluationMetricCalculator.RagMetricSnapshot> categories,
                                              double score, boolean eligible) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", profile.id());
        result.put("chunking", Map.of("parentMaxChars", profile.parentMaxChars(),
                "childMaxChars", profile.childMaxChars(), "childOverlapChars", profile.childOverlapChars()));
        result.put("rrf", Map.of("k", profile.rrfK(), "bm25Weight", profile.bm25Weight(),
                "vectorWeight", profile.vectorWeight()));
        result.put("rerankStrategy", profile.rerankStrategy());
        result.put("overall", overall);
        result.put("categories", categories);
        result.put("deltaFromBaseline", Map.of(
                "recallAt5", round(overall.recallAt5() - baseline.recallAt5()),
                "mrr", round(overall.mrr() - baseline.mrr()),
                "citationValidity", round(overall.citationValidity() - baseline.citationValidity())));
        result.put("score", score);
        result.put("eligible", eligible);
        return result;
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

    private double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }
}
