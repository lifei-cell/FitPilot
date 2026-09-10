package com.fitpilot.agent.application;

import com.fitpilot.agent.infrastructure.AgentRepository;
import com.fitpilot.observability.FitPilotMetrics;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ToolExecutionService {
    private static final Set<String> WRITE_TOOLS = Set.of("create_training_plan", "adjust_training_plan");

    private final AgentToolExecutor tools;
    private final AgentRepository repository;
    private final FitPilotMetrics metrics;

    public ToolExecutionService(AgentToolExecutor tools, AgentRepository repository, FitPilotMetrics metrics) {
        this.tools = tools;
        this.repository = repository;
        this.metrics = metrics;
    }

    public Map<String, Object> executeReadTools(UUID executionId, long userId, String message,
                                                List<String> requestedTools) {
        Map<String, Object> results = new LinkedHashMap<>();
        for (String tool : requestedTools) {
            if (WRITE_TOOLS.contains(tool)) continue;
            long started = System.nanoTime();
            try {
                Object result = tools.execute(tool, userId, message);
                results.put(tool, result);
                repository.toolCall(executionId, tool, Map.of("query", message), result,
                        "SUCCEEDED", elapsed(started));
                metrics.tool(tool, "SUCCEEDED", elapsed(started));
            } catch (RuntimeException failure) {
                Map<String, Object> unavailable = Map.of("available", false, "reason", safeMessage(failure));
                results.put(tool, unavailable);
                repository.toolCall(executionId, tool, Map.of("query", message), unavailable,
                        "FAILED", elapsed(started));
                metrics.tool(tool, "FAILED", elapsed(started));
            }
        }
        return results;
    }

    public Object executeMcp(UUID executionId, long userId, UUID sessionId, String tool, String query) {
        String normalizedQuery = query == null ? "" : query;
        long started = System.nanoTime();
        repository.startExecution(executionId, userId, sessionId, "MCP_TOOL", List.of(tool), LocalDateTime.now());
        try {
            Object result = tools.execute(tool, userId, normalizedQuery);
            repository.toolCall(executionId, tool, Map.of("query", normalizedQuery), result,
                    "SUCCEEDED", elapsed(started));
            metrics.tool(tool, "SUCCEEDED", elapsed(started));
            repository.finishExecution(executionId, "SUCCEEDED", elapsed(started), 0);
            return result;
        } catch (RuntimeException failure) {
            repository.toolCall(executionId, tool, Map.of(), Map.of("error", safeMessage(failure)),
                    "FAILED", elapsed(started));
            metrics.tool(tool, "FAILED", elapsed(started));
            repository.finishExecution(executionId, "FAILED", elapsed(started), 0);
            throw failure;
        }
    }

    private long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private String safeMessage(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
