package com.fitpilot.evaluation.application;

import com.fitpilot.rag.dto.RagDtos;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationMetricCalculatorTest {
    private final EvaluationMetricCalculator calculator = new EvaluationMetricCalculator();

    @Test
    void calculatesRankedRagMetricsAndCategoryReport() {
        var irrelevant = context("https://example.com/other");
        var relevant = context("https://example.com/expected");

        var score = calculator.scoreRagCase(
                List.of("https://example.com/expected"), List.of(irrelevant, relevant));
        var accumulator = new EvaluationMetricCalculator.RagAccumulator();
        accumulator.add("strength training", score);

        assertThat(score.recall()).isEqualTo(1);
        assertThat(score.reciprocalRank()).isEqualTo(.5);
        assertThat(score.precision()).isEqualTo(.5);
        assertThat(score.contextRecall()).isEqualTo(1);
        assertThat(score.citationValid()).isTrue();
        assertThat(accumulator.report(1))
                .containsEntry("citationValidity", 1d)
                .containsEntry("category.strength_training.mrr", .5d);
    }

    @Test
    void rejectsIncompleteCitationMetadata() {
        RagDtos.RetrievedContext context = new RagDtos.RetrievedContext(
                UUID.randomUUID(), UUID.randomUUID(), "title", "category", null,
                "content", 1, new RagDtos.Citation(UUID.randomUUID(), "", "license",
                "publisher", "OFFICIAL", 1, null), List.of("vector"));

        assertThat(calculator.scoreRagCase(List.of("expected"), List.of(context)).citationValid()).isFalse();
    }

    private RagDtos.RetrievedContext context(String sourceUrl) {
        return new RagDtos.RetrievedContext(UUID.randomUUID(), UUID.randomUUID(), "title", "category",
                null, "content", 1, new RagDtos.Citation(UUID.randomUUID(), sourceUrl, "license",
                "publisher", "OFFICIAL", 1, LocalDateTime.now().plusDays(1)), List.of("vector"));
    }
}
