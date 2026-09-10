package com.fitpilot.agent.application;

import com.fitpilot.agent.infrastructure.AgentRepository;
import com.fitpilot.llm.application.LlmGateway;
import com.fitpilot.llm.domain.LlmModels;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class IntentRouter {
    private final AgentPlanner planner;
    private final LlmGateway llm;
    private final AgentRepository repository;

    public IntentRouter(AgentPlanner planner, LlmGateway llm, AgentRepository repository) {
        this.planner = planner;
        this.llm = llm;
        this.repository = repository;
    }

    public RoutingContext begin(long userId, UUID sessionId, String message) {
        UUID executionId = UUID.randomUUID();
        LlmModels.WorkflowDecision fallback = planner.decide(message);
        repository.startExecution(executionId, userId, sessionId, fallback.intent(), fallback.tools(), LocalDateTime.now());
        return new RoutingContext(executionId, fallback);
    }

    public LlmModels.Result<LlmModels.WorkflowDecision> decide(RoutingContext context, String message) {
        LlmModels.Result<LlmModels.WorkflowDecision> result = llm.decide(
                context.executionId(), message, context.fallback());
        repository.updateDecision(context.executionId(), result.value().intent(), result.value().tools());
        recordUsage(context.executionId(), result);
        return result;
    }

    public void recordUsage(UUID executionId, LlmModels.Result<?> result) {
        repository.addLlmUsage(executionId, result.model(), result.promptVersion(), result.degraded(),
                result.inputTokens(), result.outputTokens(), result.costUsd());
    }

    public record RoutingContext(UUID executionId, LlmModels.WorkflowDecision fallback) {}
}
