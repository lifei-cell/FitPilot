package com.fitpilot.rag.embedding;

import com.fitpilot.rag.config.RagProperties;
import com.fitpilot.rag.processing.TextAnalyzer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@ConditionalOnProperty(prefix = "fitpilot.rag.embedding", name = "provider", havingValue = "LOCAL", matchIfMissing = true)
public class LocalHashingEmbeddingProvider implements EmbeddingProvider {
    private final TextAnalyzer analyzer;
    private final int dimensions;

    public LocalHashingEmbeddingProvider(TextAnalyzer analyzer, RagProperties properties) {
        this.analyzer = analyzer;
        this.dimensions = properties.getEmbedding().getDimensions();
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dimensions];
        for (String token : analyzer.tokens(text)) {
            long hash = fnv1a(token);
            int first = Math.floorMod((int) hash, dimensions);
            int second = Math.floorMod((int) (hash >>> 32), dimensions);
            float weight = token.length() > 1 ? 1.5f : 1.0f;
            vector[first] += (hash & 1) == 0 ? weight : -weight;
            vector[second] += (hash & 2) == 0 ? weight * 0.5f : -weight * 0.5f;
        }
        normalize(vector);
        return vector;
    }

    @Override
    public String name() { return "local-hashing-v1"; }

    private long fnv1a(String token) {
        long hash = 0xcbf29ce484222325L;
        for (byte value : token.getBytes(StandardCharsets.UTF_8)) {
            hash ^= Byte.toUnsignedInt(value);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private void normalize(float[] vector) {
        double sum = 0;
        for (float value : vector) sum += value * value;
        if (sum == 0) return;
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) vector[i] /= norm;
    }
}
