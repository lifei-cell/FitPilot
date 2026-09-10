package com.fitpilot.evaluation.application;

import com.fitpilot.rag.dto.RagDtos;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class EvaluationMetricCalculator {
    public RagCaseMetrics scoreRagCase(List<String> expectedSources,
                                       List<RagDtos.RetrievedContext> contexts) {
        Set<String> expected = new LinkedHashSet<>(expectedSources);
        List<String> actualSources = contexts.stream()
                .map(RagDtos.RetrievedContext::citation)
                .filter(java.util.Objects::nonNull)
                .map(RagDtos.Citation::sourceUrl)
                .toList();
        List<Integer> relevant = new ArrayList<>();
        for (int index = 0; index < contexts.size(); index++) {
            RagDtos.Citation citation = contexts.get(index).citation();
            if (citation != null && expected.contains(citation.sourceUrl())) relevant.add(index);
        }
        double recall = relevant.isEmpty() ? 0 : 1;
        double reciprocalRank = relevant.isEmpty() ? 0 : 1d / (relevant.getFirst() + 1);
        double ndcg = Math.min(1, relevant.stream().mapToDouble(rank -> 1d / log2(rank + 2)).sum());
        double precision = contexts.isEmpty() ? 0 : (double) relevant.size() / contexts.size();
        double contextRecall = expected.isEmpty() ? 0
                : (double) actualSources.stream().filter(expected::contains).distinct().count() / expected.size();
        boolean citationValid = contexts.stream().allMatch(this::validCitation);
        return new RagCaseMetrics(actualSources, recall, reciprocalRank, ndcg,
                precision, contextRecall, citationValid);
    }

    public String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    public static final class AgentAccumulator {
        private int passed;
        private int selectionCorrect;
        private int success;
        private int violations;
        private int hallucinations;

        public void add(boolean selected, boolean taskSucceeded, boolean violation, boolean hallucination) {
            if (selected) selectionCorrect++;
            if (taskSucceeded) {
                success++;
                passed++;
            }
            if (violation) violations++;
            if (hallucination) hallucinations++;
        }

        public int passed() { return passed; }

        public Map<String, Double> report(int total) {
            return Map.of(
                    "toolSelectionAccuracy", ratio(selectionCorrect, total),
                    "taskSuccessRate", ratio(success, total),
                    "constraintViolationRate", ratio(violations, total),
                    "hallucinationRate", ratio(hallucinations, total));
        }
    }

    public static final class RagAccumulator {
        private int passed;
        private double recall;
        private double reciprocalRank;
        private double ndcg;
        private double precision;
        private double contextRecall;
        private double citationValidity;
        private final Map<String, CategoryStats> categories = new LinkedHashMap<>();

        public void add(String category, RagCaseMetrics metrics) {
            if (metrics.recall() == 1 && metrics.citationValid()) passed++;
            recall += metrics.recall();
            reciprocalRank += metrics.reciprocalRank();
            ndcg += metrics.ndcg();
            precision += metrics.precision();
            contextRecall += metrics.contextRecall();
            if (metrics.citationValid()) citationValidity++;
            categories.computeIfAbsent(metricCategory(category), ignored -> new CategoryStats())
                    .add(metrics.recall(), metrics.reciprocalRank());
        }

        public int passed() { return passed; }

        public Map<String, Double> report(int total) {
            Map<String, Double> result = new LinkedHashMap<>();
            result.put("recallAt5", ratio(recall, total));
            result.put("mrr", ratio(reciprocalRank, total));
            result.put("ndcg", ratio(ndcg, total));
            result.put("contextPrecision", ratio(precision, total));
            result.put("contextRecall", ratio(contextRecall, total));
            result.put("citationValidity", ratio(citationValidity, total));
            categories.forEach((category, stats) -> {
                result.put("category." + category + ".recallAt5", ratio(stats.recall, stats.total));
                result.put("category." + category + ".mrr", ratio(stats.reciprocalRank, stats.total));
            });
            return result;
        }
    }

    public record RagCaseMetrics(List<String> actualSources, double recall, double reciprocalRank,
                                 double ndcg, double precision, double contextRecall,
                                 boolean citationValid) {}

    private boolean validCitation(RagDtos.RetrievedContext context) {
        RagDtos.Citation citation = context.citation();
        return citation != null
                && citation.sourceUrl() != null && !citation.sourceUrl().isBlank()
                && citation.sourceLicense() != null && !citation.sourceLicense().isBlank()
                && citation.documentVersion() > 0
                && Set.of("OFFICIAL", "INTERNAL", "PROFESSIONAL", "COMMUNITY").contains(citation.trustLevel())
                && (citation.expiresAt() == null || citation.expiresAt().isAfter(java.time.LocalDateTime.now()));
    }

    private double log2(double value) { return Math.log(value) / Math.log(2); }

    private static double ratio(double value, int total) {
        return total == 0 ? 0 : Math.round(value / total * 10000d) / 10000d;
    }

    private static String metricCategory(String value) {
        return (value == null || value.isBlank() ? "uncategorized" : value)
                .replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static final class CategoryStats {
        private double recall;
        private double reciprocalRank;
        private int total;

        private void add(double caseRecall, double caseReciprocalRank) {
            recall += caseRecall;
            reciprocalRank += caseReciprocalRank;
            total++;
        }
    }
}
