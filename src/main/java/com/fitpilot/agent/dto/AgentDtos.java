package com.fitpilot.agent.dto;

import com.fitpilot.plan.dto.TrainingPlanDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AgentDtos {
    private AgentDtos() {}
    public record SessionView(UUID id, LocalDateTime createdAt) {}
    public record MessageRequest(@NotBlank @Size(max = 4000) String message,
                                 @Valid TrainingPlanDtos.CreateRequest proposedPlan) {}
    public record MessageView(UUID executionId, String intent, List<String> selectedTools, String answer,
                              boolean confirmationRequired, PendingActionView pendingAction) {}
    public record PendingActionView(UUID id, String toolName, String confirmationToken,
                                    LocalDateTime expiresAt, Object preview, List<String> guardrailWarnings) {}
    public record ConfirmRequest(@NotBlank String confirmationToken) {}
    public record PreferenceRequest(@NotBlank @Size(max = 80) String key, @NotNull Object value) {}
    public record PreferenceView(String key, Object value, LocalDateTime updatedAt) {}
    public record ToolResult(String name, Object data) {}
    public record EvaluationLabel(List<String> expectedTools) {}
    public record EvaluationMetrics(long executions, double taskSuccessRate, double ruleViolationRate,
                                    long labeledExecutions, double toolSelectionAccuracy) {}
    public record McpRequest(String jsonrpc, Object id, String method, Map<String, Object> params) {}
}
