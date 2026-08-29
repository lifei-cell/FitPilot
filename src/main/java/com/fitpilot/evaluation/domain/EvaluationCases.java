package com.fitpilot.evaluation.domain;

import java.util.List;

public final class EvaluationCases {
    private EvaluationCases() {}
    public record AgentCase(String id,String query,List<String> expectedTools,List<String> forbiddenTools,
                            String expectedIntent,List<String> expectedConstraints) {}
    public record RagCase(String id,String query,List<String> expectedTerms,String category) {}
}
