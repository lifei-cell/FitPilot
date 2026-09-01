package com.fitpilot.agent.dto;

import com.fitpilot.plan.dto.TrainingPlanDtos;
import com.fitpilot.rag.dto.RagDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AgentDtos {
    private AgentDtos() {}
    public record SessionView(UUID id, LocalDateTime createdAt) {}
    public record SessionSummary(UUID id, String title, String status, Instant lastMessageAt,
                                 LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record SessionUpdateRequest(@Size(min = 1, max = 120) String title,
                                       @Pattern(regexp = "ACTIVE|ARCHIVED") String status) {}
    public record ConversationMessage(long id, String role, String content, String status,
                                      UUID executionId, Map<String, Object> metadata, Instant createdAt) {}
    public record MessagePage(List<ConversationMessage> items, Long nextBeforeId) {}
    public record MessageRequest(@NotBlank @Size(max = 4000) String message,
                                 @Valid TrainingPlanDtos.CreateRequest proposedPlan) {}
    public record MessageView(UUID executionId, String intent, List<String> selectedTools, String answer,
                              boolean confirmationRequired, PendingActionView pendingAction,
                              String model, boolean degraded, String promptVersion,
                              UUID retrievalId, List<RagDtos.Citation> citations) {}
    public record PendingActionView(UUID id, String toolName, String confirmationToken,
                                    Instant expiresAt, Object preview, List<String> guardrailWarnings) {}
    public record PendingActionSummary(UUID id, UUID sessionId, String toolName, String status,
                                       Instant expiresAt, Object preview) {}
    public record ConfirmationTokenView(UUID id, String confirmationToken, Instant expiresAt) {}
    public record ConfirmRequest(@NotBlank String confirmationToken) {}
    public record PreferenceRequest(@NotBlank @Size(max = 80) String key, @NotNull Object value) {}
    public record PreferenceView(String key, Object value, LocalDateTime updatedAt) {}
    public record ToolResult(String name, Object data) {}
    public record EvaluationLabel(List<String> expectedTools) {}
    public record EvaluationMetrics(long executions, double taskSuccessRate, double ruleViolationRate,
                                    long labeledExecutions, double toolSelectionAccuracy) {}
    public record McpRequest(String jsonrpc, Object id, String method, Map<String, Object> params) {}
}
