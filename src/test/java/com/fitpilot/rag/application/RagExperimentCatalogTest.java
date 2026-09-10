package com.fitpilot.rag.application;

import com.fitpilot.rag.config.RagProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RagExperimentCatalogTest {
    @Test
    void comparesChunkingRrfWeightsAndRerankStrategiesAroundBaseline() {
        var profiles = new RagExperimentCatalog(new RagProperties()).profiles();

        assertThat(profiles).extracting(profile -> profile.id()).containsExactly(
                "baseline", "chunk-small", "chunk-large", "rrf-bm25-heavy",
                "rrf-vector-heavy", "rerank-overlap", "rerank-none");
        assertThat(profiles.stream().map(profile -> profile.childMaxChars()).distinct().count())
                .isGreaterThanOrEqualTo(3);
        assertThat(profiles.stream().map(profile -> profile.bm25Weight()).distinct().count())
                .isGreaterThanOrEqualTo(3);
        assertThat(profiles.stream().map(profile -> profile.rerankStrategy()).distinct().count())
                .isEqualTo(3);
    }
}
