package com.fitpilot.rag.domain;

import java.util.Set;

/** Immutable retrieval settings used by offline evaluation without mutating live RAG configuration. */
public record RagTuningProfile(
        String id,
        int parentMaxChars,
        int childMaxChars,
        int childOverlapChars,
        int rrfK,
        double bm25Weight,
        double vectorWeight,
        String rerankStrategy) {
    private static final Set<String> RERANK_STRATEGIES =
            Set.of("NONE", "TERM_OVERLAP", "TERM_OVERLAP_TRUST");

    public RagTuningProfile {
        if (id == null || !id.matches("[a-z0-9-]{2,40}")) {
            throw new IllegalArgumentException("invalid RAG tuning profile id");
        }
        if (parentMaxChars < childMaxChars || childMaxChars < 100
                || childOverlapChars < 0 || childOverlapChars >= childMaxChars) {
            throw new IllegalArgumentException("invalid RAG chunking profile");
        }
        if (rrfK < 1 || !Double.isFinite(bm25Weight) || !Double.isFinite(vectorWeight)
                || bm25Weight < 0 || vectorWeight < 0 || bm25Weight + vectorWeight <= 0) {
            throw new IllegalArgumentException("invalid RRF profile");
        }
        if (rerankStrategy == null || !RERANK_STRATEGIES.contains(rerankStrategy)) {
            throw new IllegalArgumentException("invalid rerank strategy");
        }
    }
}
