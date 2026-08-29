package com.fitpilot.evaluation.dto;

import jakarta.validation.constraints.Pattern;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public final class EvaluationDtos {
    private EvaluationDtos() {}
    public record AgentRunRequest(@Pattern(regexp="RULE_WORKFLOW|ACTIVE_MODEL") String mode) {}
    public record RunView(UUID id,String type,String datasetVersion,String mode,String model,String promptVersion,
                          String status,int totalCases,int passedCases,Map<String,Double> metrics,
                          String errorMessage,LocalDateTime startedAt,LocalDateTime completedAt) {}
}
