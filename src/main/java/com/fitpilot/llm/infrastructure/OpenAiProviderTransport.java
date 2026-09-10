package com.fitpilot.llm.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class OpenAiProviderTransport {
    private final LlmProperties properties;
    private final PromptRegistry prompts;
    private final SensitiveDataRedactor redactor;
    private final ObjectMapper json;
    private final HttpClient client;

    public OpenAiProviderTransport(LlmProperties properties, PromptRegistry prompts,
                                   SensitiveDataRedactor redactor, ObjectMapper json) {
        this.properties = properties;
        this.prompts = prompts;
        this.redactor = redactor;
        this.json = json;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
    }

    public LlmModels.Completion request(LlmProperties.Endpoint endpoint, String model,
                                        LlmModels.Task task, String userPrompt,
                                        boolean jsonOutput, boolean degraded, long deadline) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("temperature", task == LlmModels.Task.TRAINING_ANALYSIS ? 0.2 : 0);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", prompts.system(task)),
                    Map.of("role", "user", "content", redactor.redact(userPrompt))));
            if (jsonOutput) body.put("response_format", Map.of("type", "json_object"));

            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint.getUrl()))
                    .timeout(requestTimeout(deadline))
                    .header("Content-Type", "application/json");
            if (!endpoint.getApiKey().isBlank()) {
                request.header("Authorization", "Bearer " + endpoint.getApiKey());
            }
            HttpResponse<String> response = client.send(
                    request.POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status / 100 != 2) {
                throw new LlmProviderException(status, retryable(status),
                        "HTTP_" + status, retryAfter(response));
            }
            JsonNode root = json.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText();
            if (content.isBlank()) throw new LlmProviderException(status, false, "EMPTY_RESPONSE", -1);
            int input = root.path("usage").path("prompt_tokens").asInt(0);
            int output = root.path("usage").path("completion_tokens").asInt(0);
            return new LlmModels.Completion(content, endpoint.getName(), model, input, output,
                    cost(endpoint, input, output), degraded);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmProviderException(null, false, "INTERRUPTED", -1, exception);
        } catch (LlmProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new LlmProviderException(null, true, "IO_ERROR", -1, exception);
        }
    }

    private Duration requestTimeout(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new OpenAiCompatibleClient.LlmUnavailableException("LLM deadline exceeded");
        long configured = TimeUnit.SECONDS.toNanos(properties.getRequestTimeoutSeconds());
        return Duration.ofNanos(Math.max(1, Math.min(configured, remaining)));
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

    private BigDecimal cost(LlmProperties.Endpoint endpoint, int input, int output) {
        BigDecimal inputCost = BigDecimal.valueOf(endpoint.getInputCostPerMillion())
                .multiply(BigDecimal.valueOf(input));
        BigDecimal outputCost = BigDecimal.valueOf(endpoint.getOutputCostPerMillion())
                .multiply(BigDecimal.valueOf(output));
        return inputCost.add(outputCost)
                .divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP);
    }

    private boolean retryable(int status) {
        return status == 429 || status == 502 || status == 503 || status == 504;
    }
}
