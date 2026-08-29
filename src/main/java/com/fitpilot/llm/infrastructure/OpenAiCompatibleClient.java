package com.fitpilot.llm.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.llm.application.ModelRouter;
import com.fitpilot.llm.application.PromptRegistry;
import com.fitpilot.llm.config.LlmProperties;
import com.fitpilot.llm.domain.LlmModels;
import com.fitpilot.llm.security.SensitiveDataRedactor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import com.fitpilot.observability.FitPilotMetrics;
import io.micrometer.observation.annotation.Observed;

@Component
public class OpenAiCompatibleClient {
    private final LlmProperties properties;
    private final ModelRouter router;
    private final PromptRegistry prompts;
    private final SensitiveDataRedactor redactor;
    private final ObjectMapper json;
    private final HttpClient client;
    private final LlmInvocationRepository audit;
    private final FitPilotMetrics metrics;
    private final Map<String, Circuit> circuits = new ConcurrentHashMap<>();

    public OpenAiCompatibleClient(LlmProperties properties, ModelRouter router, PromptRegistry prompts,
                                  SensitiveDataRedactor redactor, ObjectMapper json,
                                  LlmInvocationRepository audit, FitPilotMetrics metrics) {
        this.properties = properties; this.router = router; this.prompts = prompts;
        this.redactor = redactor; this.json = json; this.audit = audit;this.metrics=metrics;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs())).build();
    }

    @Observed(name="fitpilot.llm.request")
    public LlmModels.Completion complete(UUID executionId, LlmModels.Task task, String userPrompt, boolean jsonOutput) {
        if (!properties.isEnabled()) throw new LlmUnavailableException("LLM is disabled");
        List<LlmProperties.Endpoint> endpoints = List.of(properties.getPrimary(), properties.getFallback());
        RuntimeException last = new LlmUnavailableException("no LLM endpoint configured");
        for (int endpointIndex = 0; endpointIndex < endpoints.size(); endpointIndex++) {
            LlmProperties.Endpoint endpoint = endpoints.get(endpointIndex);
            if (!endpoint.configured()) continue;
            Circuit circuit = circuits.computeIfAbsent(endpoint.getName(), ignored -> new Circuit());
            if (circuit.open()) {
                audit.record(executionId, endpoint.getName(), router.model(endpoint, task), task.name(), prompts.version(),
                        "CIRCUIT_OPEN", 0, 0, BigDecimal.ZERO, 0, null, "CIRCUIT_OPEN");
                metrics.llm(endpoint.getName(),router.model(endpoint,task),"CIRCUIT_OPEN",0,0,0,BigDecimal.ZERO);
                last = new LlmUnavailableException("LLM circuit is open");
                continue;
            }
            for (int attempt = 0; attempt <= properties.getMaxRetries(); attempt++) {
                long started = System.nanoTime(); String model = router.model(endpoint, task);
                try {
                    LlmModels.Completion completion = request(endpoint, model, task, userPrompt, jsonOutput,
                            endpointIndex > 0 || attempt > 0);
                    circuit.success();
                    audit.record(executionId, endpoint.getName(), model, task.name(), prompts.version(), "SUCCEEDED",
                            completion.inputTokens(), completion.outputTokens(), completion.costUsd(), elapsed(started), 200, null);
                    metrics.llm(endpoint.getName(),model,"SUCCEEDED",elapsed(started),completion.inputTokens(),completion.outputTokens(),completion.costUsd());
                    return completion;
                } catch (ProviderException failure) {
                    circuit.failure(properties.getCircuitFailureThreshold(), properties.getCircuitOpenSeconds());
                    audit.record(executionId, endpoint.getName(), model, task.name(), prompts.version(), "FAILED", 0, 0,
                            BigDecimal.ZERO, elapsed(started), failure.httpStatus(), failure.code());
                    metrics.llm(endpoint.getName(),model,"FAILED",elapsed(started),0,0,BigDecimal.ZERO);
                    last = failure;
                    if (!failure.retryable() || attempt == properties.getMaxRetries()) break;
                    backoff(attempt);
                }
            }
        }
        throw new LlmUnavailableException("all LLM endpoints failed", last);
    }

    public Map<String, Object> status() {
        return Map.of(
                "enabled", properties.isEnabled(),
                "primaryConfigured", properties.getPrimary().configured(),
                "fallbackConfigured", properties.getFallback().configured(),
                "primaryCircuitOpen", circuits.getOrDefault(properties.getPrimary().getName(), new Circuit()).open(),
                "fallbackCircuitOpen", circuits.getOrDefault(properties.getFallback().getName(), new Circuit()).open(),
                "promptVersion", prompts.version());
    }

    private LlmModels.Completion request(LlmProperties.Endpoint endpoint, String model, LlmModels.Task task,
                                         String userPrompt, boolean jsonOutput, boolean degraded) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model); body.put("temperature", task == LlmModels.Task.TRAINING_ANALYSIS ? 0.2 : 0);
            body.put("messages", List.of(Map.of("role", "system", "content", prompts.system(task)),
                    Map.of("role", "user", "content", redactor.redact(userPrompt))));
            if (jsonOutput) body.put("response_format", Map.of("type", "json_object"));
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint.getUrl()))
                    .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                    .header("Content-Type", "application/json");
            if (!endpoint.getApiKey().isBlank()) request.header("Authorization", "Bearer " + endpoint.getApiKey());
            HttpResponse<String> response = client.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status / 100 != 2) throw new ProviderException(status, retryable(status), "HTTP_" + status);
            JsonNode root = json.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText();
            if (content.isBlank()) throw new ProviderException(status, false, "EMPTY_RESPONSE");
            int input = root.path("usage").path("prompt_tokens").asInt(0);
            int output = root.path("usage").path("completion_tokens").asInt(0);
            BigDecimal cost = cost(endpoint, input, output);
            return new LlmModels.Completion(content, endpoint.getName(), model, input, output, cost, degraded);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw new ProviderException(null, true, "INTERRUPTED", exception);
        } catch (ProviderException exception) { throw exception; }
        catch (Exception exception) { throw new ProviderException(null, true, "IO_ERROR", exception); }
    }
    private BigDecimal cost(LlmProperties.Endpoint endpoint, int input, int output) {
        BigDecimal in = BigDecimal.valueOf(endpoint.getInputCostPerMillion()).multiply(BigDecimal.valueOf(input));
        BigDecimal out = BigDecimal.valueOf(endpoint.getOutputCostPerMillion()).multiply(BigDecimal.valueOf(output));
        return in.add(out).divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP);
    }
    private boolean retryable(int status) { return status == 429 || status == 502 || status == 503 || status == 504; }
    private void backoff(int attempt) {
        try { Thread.sleep((long) (100 * Math.pow(2, attempt)) + ThreadLocalRandom.current().nextLong(100)); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new LlmUnavailableException("retry interrupted", exception); }
    }
    private long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }

    private static final class Circuit {
        private int failures; private long openUntil;
        synchronized boolean open() { return openUntil > System.currentTimeMillis(); }
        synchronized void success() { failures = 0; openUntil = 0; }
        synchronized void failure(int threshold, int openSeconds) {
            if (++failures >= threshold) { openUntil = System.currentTimeMillis() + openSeconds * 1000L; failures = 0; }
        }
    }
    private static final class ProviderException extends RuntimeException {
        private final Integer httpStatus; private final boolean retryable; private final String code;
        ProviderException(Integer status, boolean retryable, String code) { this(status, retryable, code, null); }
        ProviderException(Integer status, boolean retryable, String code, Throwable cause) { super(code, cause); this.httpStatus=status;this.retryable=retryable;this.code=code; }
        Integer httpStatus(){return httpStatus;} boolean retryable(){return retryable;} String code(){return code;}
    }
    public static class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String message) { super(message); }
        public LlmUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
