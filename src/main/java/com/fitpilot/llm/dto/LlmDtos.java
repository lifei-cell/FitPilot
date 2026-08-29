package com.fitpilot.llm.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public final class LlmDtos {
    private LlmDtos() {}
    public record InvocationView(UUID id,UUID executionId,String provider,String model,String taskType,
                                 String promptVersion,String status,int inputTokens,int outputTokens,
                                 BigDecimal costUsd,long latencyMs,Integer httpStatus,String errorCode,
                                 LocalDateTime createdAt) {}
}
