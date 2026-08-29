package com.fitpilot.rag.embedding;

import com.fitpilot.rag.config.RagProperties;
import com.fitpilot.rag.processing.TextAnalyzer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalHashingEmbeddingProviderTest {
    @Test
    void createsDeterministicNormalizedMultilingualVector() {
        LocalHashingEmbeddingProvider provider = new LocalHashingEmbeddingProvider(
                new TextAnalyzer(), new RagProperties());

        float[] first = provider.embed("深蹲 squat 渐进超负荷");
        float[] second = provider.embed("深蹲 squat 渐进超负荷");
        double norm = 0;
        for (float value : first) norm += value * value;

        assertThat(first).hasSize(384).containsExactly(second);
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(0.00001));
        assertThat(provider.name()).isEqualTo("local-hashing-v1");
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
