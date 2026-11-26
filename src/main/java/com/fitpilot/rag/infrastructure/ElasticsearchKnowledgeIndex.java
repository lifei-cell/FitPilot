package com.fitpilot.rag.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.rag.config.RagProperties;
import com.fitpilot.rag.domain.KnowledgeModels;
import com.fitpilot.rag.domain.KnowledgeModels.DocumentVersion;
import com.fitpilot.rag.domain.KnowledgeModels.IndexClaim;
import com.fitpilot.rag.domain.RagCorpus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    public void publish(IndexClaim claim) {
        ensureIndex();
        DocumentVersion document = claim.document();
        List<KnowledgeModels.IndexChunk> chunks = claim.chunks();
        if (chunks.size() > claim.slotCount()) {
            throw new IllegalStateException("knowledge chunk count exceeds the reserved Elasticsearch slots");
        }
        StringBuilder bulk = new StringBuilder();
        for (int slot = 0; slot < claim.slotCount(); slot++) {
            Map<String, Object> source = slot < chunks.size()
                    ? chunkSource(document, slot, chunks.get(slot)) : tombstone(document, slot);
            appendSlot(bulk, document, slot, document.indexingVersion(), source);
        }
        writeSlots(bulk, claim.slotCount());
        deleteLegacyChunks(document);
    }

    private Map<String, Object> chunkSource(DocumentVersion document, int slot, KnowledgeModels.IndexChunk chunk) {
        if (!document.id().equals(chunk.documentId()) || document.version() != chunk.version()) {
            throw new IllegalArgumentException("knowledge chunk belongs to a different document version");
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("documentId", document.id().toString());
        source.put("documentVersion", document.version());
        source.put("slot", slot);
        source.put("chunkId", chunk.chunkId().toString());
        source.put("corpusScope", chunk.corpusScope());
        source.put("lifecycleStatus", "ACTIVE");
        source.put("title", chunk.title());
        source.put("category", chunk.category());
        source.put("heading", nullable(chunk.heading()));
        source.put("content", chunk.content());
        source.put("lexicalText", chunk.lexicalText());
        source.put("sourceUrl", chunk.sourceUrl());
        source.put("sourceLicense", chunk.sourceLicense());
        source.put("publisher", nullable(chunk.publisher()));
        source.put("trustLevel", chunk.trustLevel());
        if (chunk.expiresAt() != null) source.put("expiresAt", chunk.expiresAt().toString());
        return source;
    }

    private void appendSlot(StringBuilder bulk, DocumentVersion document, int slot, long version,
                            Map<String, Object> source) {
        // The slot identity survives document revisions, while the search hit carries the current PostgreSQL chunk ID.
        bulk.append(write(Map.of("index", Map.of("_index", properties.getIndex(), "_id", document.id() + ":" + slot,
                "version", version, "version_type", "external_gte")))).append('\n');
        bulk.append(write(source)).append('\n');
    }

    private Map<String, Object> tombstone(DocumentVersion document, int slot) {
        return Map.of("documentId", document.id().toString(), "documentVersion", document.version(),
                "slot", slot, "lifecycleStatus", "DELETED");
    }

    private void writeSlots(StringBuilder bulk, int slotCount) {
        if (slotCount == 0) return;
        HttpResponse<String> response = request("POST", "/_bulk?refresh=wait_for", bulk.toString());
        if (response.statusCode() / 100 != 2) throw httpFailure("bulk index chunks", response);
        JsonNode result = read(response.body());
        if (!result.path("errors").isBoolean() || result.path("items").size() != slotCount) {
            throw new IllegalStateException("invalid Elasticsearch bulk response");
        }
        if (result.path("errors").asBoolean()) {
            throw new IllegalStateException("Elasticsearch bulk response contains item errors");
        }
    }

    public List<KnowledgeModels.SearchHit> search(String lexicalQuery, String category, int limit,
                                                  RagCorpus corpus) {
        ensureIndex();
        Map<String, Object> textQuery = Map.of("multi_match", Map.of(
                "query", lexicalQuery, "fields", List.of("lexicalText^3", "title^2", "heading^2"),
                "type", "best_fields", "operator", "or"));
        List<Object> filters = new ArrayList<>();
        filters.add(Map.of("term", Map.of("corpusScope", corpus.key())));
        filters.add(Map.of("term", Map.of("lifecycleStatus", "ACTIVE")));
        filters.add(Map.of("bool", Map.of("should", List.of(
                Map.of("bool", Map.of("must_not", Map.of("exists", Map.of("field", "expiresAt")))),
                Map.of("range", Map.of("expiresAt", Map.of("gt", "now")))), "minimum_should_match", 1)));
        if (category != null && !category.isBlank()) filters.add(Map.of("term", Map.of("category", category)));
        Object query = Map.of("bool", Map.of("must", textQuery, "filter", filters));
        String body = write(Map.of("size", limit, "_source", List.of("chunkId"), "query", query));
        HttpResponse<String> response = request("POST", "/" + properties.getIndex() + "/_search", body);
        if (response.statusCode() / 100 != 2) throw httpFailure("search index", response);
        try {
            List<KnowledgeModels.SearchHit> hits = new ArrayList<>();
            for (JsonNode hit : json.readTree(response.body()).path("hits").path("hits")) {
                String chunkId = hit.path("_source").path("chunkId").asText(hit.path("_id").asText());
                hits.add(new KnowledgeModels.SearchHit(UUID.fromString(chunkId),
                        hit.path("_score").asDouble()));
            }
            return hits;
        } catch (Exception exception) {
            throw new IllegalStateException("invalid Elasticsearch search response", exception);
        }
    }

    public void deleteDocument(DocumentVersion document, int slotCount) {
        ensureIndex();
        // Keep the external version even after Elasticsearch's physical deletion tombstone would expire.
        StringBuilder bulk = new StringBuilder();
        for (int slot = 0; slot < slotCount; slot++) {
            appendSlot(bulk, document, slot, document.deletionVersion(), tombstone(document, slot));
        }
        writeSlots(bulk, slotCount);
        deleteLegacyChunks(document);
    }

    private void deleteLegacyChunks(DocumentVersion document) {
        String body = write(Map.of("query", Map.of("bool", Map.of(
                "must_not", Map.of("exists", Map.of("field", "slot")),
                "filter", Map.of("term", Map.of("documentId", document.id().toString())),
                "should", List.of(
                        Map.of("range", Map.of("documentVersion", Map.of("lte", document.version()))),
                        Map.of("bool", Map.of("must_not", Map.of("exists", Map.of("field", "documentVersion"))))),
                "minimum_should_match", 1))));
        HttpResponse<String> response = request("POST", "/" + properties.getIndex()
                + "/_delete_by_query?conflicts=proceed&refresh=true", body);
        if (response.statusCode() / 100 != 2) throw httpFailure("delete legacy chunks", response);
        JsonNode result = read(response.body());
        if (result.path("timed_out").asBoolean() || result.path("version_conflicts").asLong() != 0
                || !result.path("failures").isArray() || !result.path("failures").isEmpty()) {
            throw new IllegalStateException("Elasticsearch legacy chunk cleanup was incomplete");
        }
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

    private JsonNode read(String value) {
        try { return json.readTree(value); }
        catch (Exception exception) { throw new IllegalStateException("invalid Elasticsearch response", exception); }
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
                Map.entry("slot", Map.of("type", "integer")), Map.entry("chunkId", Map.of("type", "keyword")),
                Map.entry("corpusScope", Map.of("type", "keyword")),
                Map.entry("lifecycleStatus", Map.of("type", "keyword")), Map.entry("expiresAt", Map.of("type", "date")));
    }
    private String trimSlash(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
    private IllegalStateException httpFailure(String operation, HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body();
        return new IllegalStateException("Elasticsearch " + operation + " failed: HTTP " + response.statusCode()
                + " " + body.substring(0, Math.min(body.length(), 500)));
    }
}
