package com.fitpilot.rag.domain;

import java.util.UUID;

/** The live corpus has no evaluation ID. Each evaluation attempt owns a separate corpus. */
public record RagCorpus(UUID evaluationId) {
    public static final RagCorpus LIVE = new RagCorpus(null);

    public static RagCorpus evaluation() {
        return new RagCorpus(UUID.randomUUID());
    }

    public String key() {
        return evaluationId == null ? "live" : "evaluation:" + evaluationId;
    }
}
