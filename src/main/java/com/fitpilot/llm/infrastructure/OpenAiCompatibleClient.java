package com.fitpilot.llm.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.llm.application.ModelRouter;
import com.fitpilot.llm.application.PromptRegistry;
import com.fitpilot.llm.config.LlmProperties;
import com.fitpilot.llm.domain.LlmModels;
import com.fitpilot.llm.security.SensitiveDataRedactor;
import com.fitpilot.observability.FitPilotMetrics;
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

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
    private final Semaphore bulkhead;

    public OpenAiCompatibleClient(LlmProperties properties, ModelRouter router, PromptRegistry prompts,
                                  SensitiveDataRedactor redactor, ObjectMapper json,
                                  LlmInvocationRepository audit, FitPilotMetrics metrics) {
        this.properties = properties;
        this.router = router;
        this.prompts = prompts;
        this.redactor = redactor;
        this.json = json;
        this.audit = audit;
        this.metrics = metrics;
        this.bulkhead = new Semaphore(properties.getMaxConcurrentRequests(), true);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs())).build();
    }

    @Observed(name = "fitpilot.llm.request")
    public LlmModels.Completion complete(UUID executionId, LlmModels.Task task,
                                         String userPrompt, boolean jsonOutput) {
        if (!properties.isEnabled()) throw new LlmUnavailableException("LLM is disabled");
        acquireBulkhead();
        try {
            return completeWithinDeadline(executionId, task, userPrompt, jsonOutput);
        } finally {
            bulkhead.release();
        }
    }

    private LlmModels.Completion completeWithinDeadline(UUID executionId, LlmModels.Task task,
                                                         String userPrompt, boolean jsonOutput) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(properties.getTotalTimeoutSeconds());
        List<LlmProperties.Endpoint> endpoints = List.of(properties.getPrimary(), properties.getFallback());
        RuntimeException last = new LlmUnavailableException("no LLM endpoint configured");

        for (int endpointIndex = 0; endpointIndex < endpoints.size(); endpointIndex++) {
            ensureActive(deadline);
            LlmProperties.Endpoint endpoint = endpoints.get(endpointIndex);
            if (!endpoint.configured()) continue;
            Circuit circuit = circuits.computeIfAbsent(endpoint.getName(), ignored -> new Circuit());
            Circuit.Permit permit = circuit.acquire();
            String model = router.model(endpoint, task);
            if (permit == Circuit.Permit.DENIED) {
                record(executionId, endpoint, model, task, "CIRCUIT_OPEN", 0, null, "CIRCUIT_OPEN");
                last = new LlmUnavailableException("LLM circuit is open");
                continue;
            }

            int maxAttempts = permit == Circuit.Permit.HALF_OPEN ? 1 : properties.getMaxRetries() + 1;
            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                ensureActive(deadline);
                long started = System.nanoTime();
                try {
                    LlmModels.Completion completion = request(endpoint, model, task, userPrompt, jsonOutput,
                            endpointIndex > 0 || attempt > 0, deadline);
                    circuit.success(permit);
                    audit.record(executionId, endpoint.getName(), model, task.name(), prompts.version(), "SUCCEEDED",
                            completion.inputTokens(), completion.outputTokens(), completion.costUsd(),
                            elapsed(started), 200, null);
                    metrics.llm(endpoint.getName(), model, "SUCCEEDED", elapsed(started),
                            completion.inputTokens(), completion.outputTokens(), completion.costUsd());
                    return completion;
                } catch (ProviderException failure) {
                    boolean circuitOpened = false;
                    if (failure.retryable()) {
                        circuitOpened = circuit.failure(permit, properties.getCircuitFailureThreshold(),
                                properties.getCircuitOpenSeconds());
                    } else {
                        circuit.success(permit);
                    }
                    record(executionId, endpoint, model, task, "FAILED", elapsed(started),
                            failure.httpStatus(), failure.code());
                    last = failure;
                    if ("INTERRUPTED".equals(failure.code())) {
                        throw new LlmUnavailableException("LLM request cancelled", failure);
                    }
                    if (!failure.retryable() || circuitOpened || attempt + 1 >= maxAttempts) break;
                    sleepBeforeRetry(failure.retryAfterMs(), attempt, deadline);
                    permit = Circuit.Permit.CLOSED;
                }
            }
        }
        ensureActive(deadline);
        throw new LlmUnavailableException("all LLM endpoints failed", last);
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", properties.isEnabled());
        status.put("primaryConfigured", properties.getPrimary().configured());
        status.put("fallbackConfigured", properties.getFallback().configured());
        String primaryCircuit = circuitState(properties.getPrimary().getName());
        String fallbackCircuit = circuitState(properties.getFallback().getName());
        status.put("primaryCircuit", primaryCircuit);
        status.put("fallbackCircuit", fallbackCircuit);
        status.put("primaryCircuitOpen", "OPEN".equals(primaryCircuit));
        status.put("fallbackCircuitOpen", "OPEN".equals(fallbackCircuit));
        status.put("totalTimeoutSeconds", properties.getTotalTimeoutSeconds());
        status.put("maxConcurrentRequests", properties.getMaxConcurrentRequests());
        status.put("availablePermits", bulkhead.availablePermits());
        status.put("promptVersion", prompts.version());
        return status;
    }

    private LlmModels.Completion request(LlmProperties.Endpoint endpoint, String model, LlmModels.Task task,
                                         String userPrompt, boolean jsonOutput, boolean degraded, long deadline) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("temperature", task == LlmModels.Task.TRAINING_ANALYSIS ? 0.2 : 0);
            body.put("messages", List.of(Map.of("role", "system", "content", prompts.system(task)),
                    Map.of("role", "user", "content", redactor.redact(userPrompt))));
            if (jsonOutput) body.put("response_format", Map.of("type", "json_object"));
            Duration timeout = requestTimeout(deadline);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint.getUrl()))
                    .timeout(timeout).header("Content-Type", "application/json");
            if (!endpoint.getApiKey().isBlank()) {
                request.header("Authorization", "Bearer " + endpoint.getApiKey());
            }
            HttpResponse<String> response = client.send(
                    request.POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status / 100 != 2) {
                throw new ProviderException(status, retryable(status), "HTTP_" + status,
                        retryAfter(response));
            }
            JsonNode root = json.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText();
            if (content.isBlank()) throw new ProviderException(status, false, "EMPTY_RESPONSE", -1);
            int input = root.path("usage").path("prompt_tokens").asInt(0);
            int output = root.path("usage").path("completion_tokens").asInt(0);
            return new LlmModels.Completion(content, endpoint.getName(), model, input, output,
                    cost(endpoint, input, output), degraded);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProviderException(null, false, "INTERRUPTED", -1, exception);
        } catch (ProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ProviderException(null, true, "IO_ERROR", -1, exception);
        }
    }

    private void acquireBulkhead() {
        try {
            if (!bulkhead.tryAcquire(properties.getBulkheadAcquireTimeoutMs(), TimeUnit.MILLISECONDS)) {
                throw new LlmUnavailableException("LLM bulkhead is full");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("LLM request cancelled", exception);
        }
    }

    private Duration requestTimeout(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new LlmUnavailableException("LLM deadline exceeded");
        long configured = TimeUnit.SECONDS.toNanos(properties.getRequestTimeoutSeconds());
        return Duration.ofNanos(Math.max(1, Math.min(configured, remaining)));
    }

    private void ensureActive(long deadline) {
        if (Thread.currentThread().isInterrupted()) {
            throw new LlmUnavailableException("LLM request cancelled");
        }
        if (System.nanoTime() >= deadline) {
            throw new LlmUnavailableException("LLM deadline exceeded");
        }
    }

    private long retryAfter(HttpResponse<?> response) {
        String raw = response.headers().firstValue("Retry-After").orElse("").trim();
        if (raw.isEmpty()) return -1;
        try {
            return Math.min(properties.getMaxRetryAfterMs(), Math.max(0, Long.parseLong(raw) * 1000));
        } catch (NumberFormatException ignored) {
            try {
                long millis = Duration.between(Instant.now(),
                        ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis();
                return Math.min(properties.getMaxRetryAfterMs(), Math.max(0, millis));
            } catch (RuntimeException invalidDate) {
                return -1;
            }
        }
    }

    private void sleepBeforeRetry(long retryAfterMs, int attempt, long deadline) {
        long jitteredBackoff = (long) (100 * Math.pow(2, attempt)) + ThreadLocalRandom.current().nextLong(100);
        long delay = retryAfterMs >= 0 ? retryAfterMs : jitteredBackoff;
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(Math.max(0, deadline - System.nanoTime()));
        if (remainingMs <= 0 || delay >= remainingMs) {
            throw new LlmUnavailableException("LLM deadline exceeded");
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("LLM request cancelled", exception);
        }
    }

    private void record(UUID executionId, LlmProperties.Endpoint endpoint, String model, LlmModels.Task task,
                        String status, long latency, Integer httpStatus, String code) {
        audit.record(executionId, endpoint.getName(), model, task.name(), prompts.version(), status,
                0, 0, BigDecimal.ZERO, latency, httpStatus, code);
        metrics.llm(endpoint.getName(), model, status, latency, 0, 0, BigDecimal.ZERO);
    }

    private String circuitState(String endpointName) {
        Circuit circuit = circuits.get(endpointName);
        return circuit == null ? Circuit.State.CLOSED.name() : circuit.state();
    }

    private BigDecimal cost(LlmProperties.Endpoint endpoint, int input, int output) {
        BigDecimal in = BigDecimal.valueOf(endpoint.getInputCostPerMillion()).multiply(BigDecimal.valueOf(input));
        BigDecimal out = BigDecimal.valueOf(endpoint.getOutputCostPerMillion()).multiply(BigDecimal.valueOf(output));
        return in.add(out).divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP);
    }

    private boolean retryable(int status) {
        return status == 429 || status == 502 || status == 503 || status == 504;
    }

    private long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }

    private static final class Circuit {
        enum State { CLOSED, OPEN, HALF_OPEN }
        enum Permit { CLOSED, HALF_OPEN, DENIED }

        private int failures;
        private long openUntil;
        private State state = State.CLOSED;
        private boolean halfOpenProbe;

        synchronized Permit acquire() {
            if (state == State.CLOSED) return Permit.CLOSED;
            if (state == State.OPEN && System.currentTimeMillis() >= openUntil) {
                state = State.HALF_OPEN;
                halfOpenProbe = false;
            }
            if (state == State.HALF_OPEN && !halfOpenProbe) {
                halfOpenProbe = true;
                return Permit.HALF_OPEN;
            }
            return Permit.DENIED;
        }

        synchronized void success(Permit permit) {
            failures = 0;
            openUntil = 0;
            halfOpenProbe = false;
            state = State.CLOSED;
        }

        synchronized boolean failure(Permit permit, int threshold, int openSeconds) {
            if (permit == Permit.HALF_OPEN || ++failures >= threshold) {
                state = State.OPEN;
                openUntil = System.currentTimeMillis() + openSeconds * 1000L;
                halfOpenProbe = false;
                failures = 0;
                return true;
            }
            return false;
        }

        synchronized String state() {
            if (state == State.OPEN && System.currentTimeMillis() >= openUntil) return State.HALF_OPEN.name();
            return state.name();
        }
    }

    private static final class ProviderException extends RuntimeException {
        private final Integer httpStatus;
        private final boolean retryable;
        private final String code;
        private final long retryAfterMs;

        ProviderException(Integer status, boolean retryable, String code, long retryAfterMs) {
            this(status, retryable, code, retryAfterMs, null);
        }

        ProviderException(Integer status, boolean retryable, String code, long retryAfterMs, Throwable cause) {
            super(code, cause);
            this.httpStatus = status;
            this.retryable = retryable;
            this.code = code;
            this.retryAfterMs = retryAfterMs;
        }

        Integer httpStatus() { return httpStatus; }
        boolean retryable() { return retryable; }
        String code() { return code; }
        long retryAfterMs() { return retryAfterMs; }
    }

    public static class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String message) { super(message); }
        public LlmUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
