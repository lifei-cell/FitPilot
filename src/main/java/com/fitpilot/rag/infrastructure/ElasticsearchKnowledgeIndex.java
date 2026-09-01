package com.fitpilot.rag.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.rag.config.RagProperties;
import com.fitpilot.rag.domain.KnowledgeModels;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "fitpilot.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ElasticsearchKnowledgeIndex {
    private final RagProperties.Elasticsearch properties;
    private final ObjectMapper json;
    private final HttpClient client;
    private volatile boolean indexReady;

    public ElasticsearchKnowledgeIndex(RagProperties ragProperties, ObjectMapper json) {
        this.properties = ragProperties.getElasticsearch();
        this.json = json;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs())).build();
        if (!properties.getIndex().matches("[a-z0-9._-]+")) {
            throw new IllegalStateException("invalid Elasticsearch index name");
        }
    }

    public synchronized void ensureIndex() {
        if (indexReady) return;
        HttpResponse<String> head = request("HEAD", "/" + properties.getIndex(), null);
        if (head.statusCode() == 200) {
            HttpResponse<String> updated = request("PUT", "/" + properties.getIndex() + "/_mapping",
                    write(Map.of("properties", mappingProperties())));
            if (updated.statusCode() / 100 != 2) throw httpFailure("update mapping", updated);
            indexReady = true;
            return;
        }
        if (head.statusCode() != 404) throw httpFailure("inspect index", head);
        Map<String, Object> mapping = Map.of(
                "mappings", Map.of("dynamic", "strict", "properties", mappingProperties()));
        HttpResponse<String> created = request("PUT", "/" + properties.getIndex(), write(mapping));
        if (created.statusCode() / 100 == 2) { indexReady = true; return; }
        if (created.statusCode() == 400
                && request("HEAD", "/" + properties.getIndex(), null).statusCode() == 200) { indexReady = true; return; }
        throw httpFailure("create index", created);
    }

    public void replaceDocument(UUID documentId, List<KnowledgeModels.IndexChunk> chunks) {
        ensureIndex();
        Map<String, Object> deleteQuery = Map.of("query", Map.of("term", Map.of("documentId", documentId.toString())));
        HttpResponse<String> deleted = request("POST", "/" + properties.getIndex()
                + "/_delete_by_query?conflicts=proceed&refresh=true", write(deleteQuery));
        if (deleted.statusCode() / 100 != 2) throw httpFailure("delete old chunks", deleted);
        if (chunks.isEmpty()) return;

        StringBuilder bulk = new StringBuilder();
        for (KnowledgeModels.IndexChunk chunk : chunks) {
            bulk.append(write(Map.of("index", Map.of("_index", properties.getIndex(), "_id", chunk.chunkId().toString()))))
                    .append('\n');
            Map<String, Object> source = new java.util.LinkedHashMap<>();
            source.put("documentId", chunk.documentId().toString()); source.put("title", chunk.title());
            source.put("category", chunk.category()); source.put("heading", nullable(chunk.heading()));
            source.put("content", chunk.content()); source.put("lexicalText", chunk.lexicalText());
            source.put("sourceUrl", chunk.sourceUrl()); source.put("sourceLicense", chunk.sourceLicense());
            source.put("publisher", nullable(chunk.publisher())); source.put("trustLevel", chunk.trustLevel());
            source.put("documentVersion", chunk.version()); source.put("lifecycleStatus", "ACTIVE");
            if (chunk.expiresAt() != null) source.put("expiresAt", chunk.expiresAt().toString());
            bulk.append(write(source)).append('\n');
        }
        HttpResponse<String> response = request("POST", "/_bulk?refresh=wait_for", bulk.toString());
        if (response.statusCode() / 100 != 2) throw httpFailure("bulk index chunks", response);
        try {
            if (json.readTree(response.body()).path("errors").asBoolean()) {
                throw new IllegalStateException("Elasticsearch bulk response contains item errors");
            }
        } catch (Exception exception) {
            throw new IllegalStateException("invalid Elasticsearch bulk response", exception);
        }
    }

    public List<KnowledgeModels.SearchHit> search(String lexicalQuery, String category, int limit) {
        ensureIndex();
        Map<String, Object> textQuery = Map.of("multi_match", Map.of(
                "query", lexicalQuery, "fields", List.of("lexicalText^3", "title^2", "heading^2"),
                "type", "best_fields", "operator", "or"));
        List<Object> filters = new ArrayList<>();
        filters.add(Map.of("term", Map.of("lifecycleStatus", "ACTIVE")));
        filters.add(Map.of("bool", Map.of("should", List.of(
                Map.of("bool", Map.of("must_not", Map.of("exists", Map.of("field", "expiresAt")))),
                Map.of("range", Map.of("expiresAt", Map.of("gt", "now")))), "minimum_should_match", 1)));
        if (category != null && !category.isBlank()) filters.add(Map.of("term", Map.of("category", category)));
        Object query = Map.of("bool", Map.of("must", textQuery, "filter", filters));
        String body = write(Map.of("size", limit, "_source", false, "query", query));
        HttpResponse<String> response = request("POST", "/" + properties.getIndex() + "/_search", body);
        if (response.statusCode() / 100 != 2) throw httpFailure("search index", response);
        try {
            List<KnowledgeModels.SearchHit> hits = new ArrayList<>();
            for (JsonNode hit : json.readTree(response.body()).path("hits").path("hits")) {
                hits.add(new KnowledgeModels.SearchHit(UUID.fromString(hit.path("_id").asText()),
                        hit.path("_score").asDouble()));
            }
            return hits;
        } catch (Exception exception) {
            throw new IllegalStateException("invalid Elasticsearch search response", exception);
        }
    }

    public void deleteDocument(UUID documentId) {
        ensureIndex();
        String body = write(Map.of("query", Map.of("term", Map.of("documentId", documentId.toString()))));
        HttpResponse<String> response = request("POST", "/" + properties.getIndex()
                + "/_delete_by_query?conflicts=proceed&refresh=true", body);
        if (response.statusCode() / 100 != 2) throw httpFailure("delete document", response);
    }

    private HttpResponse<String> request(String method, String path, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(trimSlash(properties.getUrl()) + path))
                    .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                    .header("Accept", "application/json");
            if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
            else builder.header("Content-Type", path.contains("_bulk") ? "application/x-ndjson" : "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Elasticsearch request interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Elasticsearch request failed", exception);
        }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("cannot serialize Elasticsearch request", exception); }
    }

    private Object nullable(String value) { return value == null ? "" : value; }
    private Map<String, Object> mappingProperties() {
        return Map.ofEntries(
                Map.entry("documentId", Map.of("type", "keyword")), Map.entry("title", Map.of("type", "text")),
                Map.entry("category", Map.of("type", "keyword")), Map.entry("heading", Map.of("type", "text")),
                Map.entry("content", Map.of("type", "text", "index", false)), Map.entry("lexicalText", Map.of("type", "text")),
                Map.entry("sourceUrl", Map.of("type", "keyword", "index", false)),
                Map.entry("sourceLicense", Map.of("type", "keyword", "index", false)),
                Map.entry("publisher", Map.of("type", "keyword", "index", false)),
                Map.entry("trustLevel", Map.of("type", "keyword")),
                Map.entry("documentVersion", Map.of("type", "integer")),
                Map.entry("lifecycleStatus", Map.of("type", "keyword")), Map.entry("expiresAt", Map.of("type", "date")));
    }
    private String trimSlash(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
    private IllegalStateException httpFailure(String operation, HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body();
        return new IllegalStateException("Elasticsearch " + operation + " failed: HTTP " + response.statusCode()
                + " " + body.substring(0, Math.min(body.length(), 500)));
    }
}
