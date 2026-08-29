package com.fitpilot.rag.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.rag.config.RagProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "fitpilot.rag.embedding", name = "provider", havingValue = "OPENAI_COMPATIBLE")
public class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {
    private final RagProperties.Embedding properties;
    private final ObjectMapper json;
    private final HttpClient client;

    public OpenAiCompatibleEmbeddingProvider(RagProperties ragProperties, ObjectMapper json) {
        this.properties = ragProperties.getEmbedding();
        this.json = json;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds())).build();
        if (properties.getApiUrl().isBlank() || properties.getApiKey().isBlank()) {
            throw new IllegalStateException("EMBEDDING_API_URL and EMBEDDING_API_KEY are required");
        }
    }

    @Override
    public float[] embed(String text) {
        try {
            byte[] body = json.writeValueAsBytes(Map.of(
                    "model", properties.getModel(), "input", text, "dimensions", properties.getDimensions()));
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getApiUrl()))
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("embedding provider returned HTTP " + response.statusCode());
            }
            JsonNode values = json.readTree(response.body()).path("data").path(0).path("embedding");
            if (values.size() != properties.getDimensions()) {
                throw new IllegalStateException("embedding dimension mismatch: " + values.size());
            }
            float[] result = new float[values.size()];
            for (int i = 0; i < values.size(); i++) result[i] = (float) values.get(i).asDouble();
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("embedding request interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("embedding request failed", exception);
        }
    }

    @Override
    public String name() { return "openai-compatible:" + properties.getModel(); }
}
