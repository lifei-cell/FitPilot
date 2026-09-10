package com.fitpilot.llm.infrastructure;

import com.fitpilot.llm.application.ModelRouter;
import com.fitpilot.llm.application.PromptRegistry;
import com.fitpilot.llm.config.LlmProperties;
import com.fitpilot.llm.domain.LlmModels;
import com.fitpilot.observability.FitPilotMetrics;
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Coordinates endpoint routing, resilience, provider transport and invocation audit. */
@Component
public class OpenAiCompatibleClient {
    private final LlmProperties properties;
    private final ModelRouter router;
    private final PromptRegistry prompts;
    private final OpenAiProviderTransport transport;
    private final LlmResiliencePolicy resilience;
    private final LlmInvocationRepository audit;
    private final FitPilotMetrics metrics;

    public OpenAiCompatibleClient(LlmProperties properties, ModelRouter router, PromptRegistry prompts,
                                  OpenAiProviderTransport transport, LlmResiliencePolicy resilience,
                                  LlmInvocationRepository audit, FitPilotMetrics metrics) {
        this.properties = properties;
        this.router = router;
        this.prompts = prompts;
        this.transport = transport;
        this.resilience = resilience;
        this.audit = audit;
        this.metrics = metrics;
    }

    @Observed(name = "fitpilot.llm.request")
    public LlmModels.Completion complete(UUID executionId, LlmModels.Task task,
                                         String userPrompt, boolean jsonOutput) {
        if (!properties.isEnabled()) throw new LlmUnavailableException("LLM is disabled");
        resilience.acquireBulkhead();
        try {
            return completeWithinDeadline(executionId, task, userPrompt, jsonOutput);
        } finally {
            resilience.releaseBulkhead();
        }
    }

    private LlmModels.Completion completeWithinDeadline(UUID executionId, LlmModels.Task task,
                                                         String userPrompt, boolean jsonOutput) {
        long deadline = resilience.deadline();
        List<LlmProperties.Endpoint> endpoints = List.of(properties.getPrimary(), properties.getFallback());
        RuntimeException last = new LlmUnavailableException("no LLM endpoint configured");

        for (int endpointIndex = 0; endpointIndex < endpoints.size(); endpointIndex++) {
            resilience.ensureActive(deadline);
            LlmProperties.Endpoint endpoint = endpoints.get(endpointIndex);
            if (!endpoint.configured()) continue;
            LlmResiliencePolicy.CircuitPermit permit = resilience.acquireCircuit(endpoint.getName());
            String model = router.model(endpoint, task);
            if (permit == LlmResiliencePolicy.CircuitPermit.DENIED) {
                record(executionId, endpoint, model, task, "CIRCUIT_OPEN", 0, null, "CIRCUIT_OPEN");
                last = new LlmUnavailableException("LLM circuit is open");
                continue;
            }

            int maxAttempts = permit == LlmResiliencePolicy.CircuitPermit.HALF_OPEN
                    ? 1 : properties.getMaxRetries() + 1;
            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                resilience.ensureActive(deadline);
                long started = System.nanoTime();
                try {
                    LlmModels.Completion completion = transport.request(endpoint, model, task,
                            userPrompt, jsonOutput, endpointIndex > 0 || attempt > 0, deadline);
                    resilience.success(endpoint.getName(), permit);
                    audit.record(executionId, endpoint.getName(), model, task.name(), prompts.version(),
                            "SUCCEEDED", completion.inputTokens(), completion.outputTokens(), completion.costUsd(),
                            elapsed(started), 200, null);
                    metrics.llm(endpoint.getName(), model, "SUCCEEDED", elapsed(started),
                            completion.inputTokens(), completion.outputTokens(), completion.costUsd());
                    return completion;
                } catch (LlmProviderException failure) {
                    boolean circuitOpened = false;
                    if (failure.retryable()) {
                        circuitOpened = resilience.failure(endpoint.getName(), permit);
                    } else {
                        resilience.success(endpoint.getName(), permit);
                    }
                    record(executionId, endpoint, model, task, "FAILED", elapsed(started),
                            failure.httpStatus(), failure.code());
                    last = failure;
                    if ("INTERRUPTED".equals(failure.code())) {
                        throw new LlmUnavailableException("LLM request cancelled", failure);
                    }
                    if (!failure.retryable() || circuitOpened || attempt + 1 >= maxAttempts) break;
                    resilience.sleepBeforeRetry(failure.retryAfterMs(), attempt, deadline);
                    permit = LlmResiliencePolicy.CircuitPermit.CLOSED;
                }
            }
        }
        resilience.ensureActive(deadline);
        throw new LlmUnavailableException("all LLM endpoints failed", last);
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", properties.isEnabled());
        status.put("primaryConfigured", properties.getPrimary().configured());
        status.put("fallbackConfigured", properties.getFallback().configured());
        String primaryCircuit = resilience.circuitState(properties.getPrimary().getName());
        String fallbackCircuit = resilience.circuitState(properties.getFallback().getName());
        status.put("primaryCircuit", primaryCircuit);
        status.put("fallbackCircuit", fallbackCircuit);
        status.put("primaryCircuitOpen", "OPEN".equals(primaryCircuit));
        status.put("fallbackCircuitOpen", "OPEN".equals(fallbackCircuit));
        status.put("totalTimeoutSeconds", properties.getTotalTimeoutSeconds());
        status.put("maxConcurrentRequests", properties.getMaxConcurrentRequests());
        status.put("availablePermits", resilience.availablePermits());
        status.put("promptVersion", prompts.version());
        return status;
    }

    private void record(UUID executionId, LlmProperties.Endpoint endpoint, String model,
                        LlmModels.Task task, String status, long latency,
                        Integer httpStatus, String code) {
        audit.record(executionId, endpoint.getName(), model, task.name(), prompts.version(), status,
                0, 0, BigDecimal.ZERO, latency, httpStatus, code);
        metrics.llm(endpoint.getName(), model, status, latency, 0, 0, BigDecimal.ZERO);
    }

    private long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    public static class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String message) { super(message); }
        public LlmUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
