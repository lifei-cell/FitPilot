package com.fitpilot.llm.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class LlmModels {
    private LlmModels() {}
    public enum Task { INTENT_CLASSIFICATION, QUERY_REWRITE, MEMORY_EXTRACTION, TRAINING_ANALYSIS, PLAN_GENERATION }
    public record ToolCall(String name, Map<String, Object> arguments) {}
    public record AgentDecision(String intent, List<ToolCall> toolCalls, String responseMode) {}
    public record Completion(String content, String provider, String model, int inputTokens, int outputTokens,
                             BigDecimal costUsd, boolean degraded) {}
    public record Result<T>(T value, String model, boolean degraded, String promptVersion,
                            int inputTokens, int outputTokens, BigDecimal costUsd) {
        public static <T> Result<T> rule(T value, String version) {
            return new Result<>(value, "RULE_WORKFLOW", true, version, 0, 0, BigDecimal.ZERO);
        }
    }
}
