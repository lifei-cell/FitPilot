package com.fitpilot.rag.embedding;

public interface EmbeddingProvider {
    float[] embed(String text);
    String name();
}
