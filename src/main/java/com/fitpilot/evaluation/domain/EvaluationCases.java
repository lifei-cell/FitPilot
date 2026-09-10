package com.fitpilot.evaluation.domain;

import com.fitpilot.rag.domain.RagTuningProfile;

import java.util.List;
import java.util.UUID;

public final class EvaluationCases {
    private EvaluationCases() {}
    public record AgentCase(String id,String query,List<String> expectedTools,List<String> forbiddenTools,
                            String expectedIntent,List<String> expectedConstraints) {}
    public record RagCase(String id, String query, List<String> expectedTerms,
                          List<String> expectedSourceUrls, String category) {}
    public record RagDocumentRef(UUID documentId, int version, String sourceUrl, String category) {}
    public record RagExperiment(List<RagTuningProfile> profiles, List<RagDocumentRef> documents) {
        public RagExperiment {
            profiles = profiles == null ? List.of() : List.copyOf(profiles);
            documents = documents == null ? List.of() : List.copyOf(documents);
        }
        public static RagExperiment none() { return new RagExperiment(List.of(), List.of()); }
        public boolean enabled() { return !profiles.isEmpty(); }
    }
}
