package com.fitpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.rag.application.HybridRetrievalService;
import com.fitpilot.rag.application.KnowledgeIngestionService;
import com.fitpilot.rag.domain.KnowledgeModels.DocumentVersion;
import com.fitpilot.rag.domain.KnowledgeModels.IndexClaim;
import com.fitpilot.rag.domain.KnowledgeModels.SearchHit;
import com.fitpilot.rag.domain.KnowledgeModels.StoredChunk;
import com.fitpilot.rag.domain.RagCorpus;
import com.fitpilot.rag.dto.RagDtos;
import com.fitpilot.rag.embedding.EmbeddingProvider;
import com.fitpilot.rag.infrastructure.ElasticsearchKnowledgeIndex;
import com.fitpilot.rag.infrastructure.KnowledgeRepository;
import com.fitpilot.rag.infrastructure.RagGovernanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FitPilotRagFlowIT {
    private static final String OPERATIONS_TOKEN = "rag-integration-operations-token";
    private static final String VERSION_TEST_CATEGORY = "version-fence";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    @Container
    static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            "docker.elastic.co/elasticsearch/elasticsearch:8.17.4")
            .withEnv("xpack.security.enabled", "false");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("security.jwt.secret", () -> "integration-test-secret-key-with-32-bytes-minimum");
        registry.add("fitpilot.events.enabled", () -> "false");
        registry.add("fitpilot.performance.rate-limit.enabled", () -> "false");
        registry.add("fitpilot.rag.enabled", () -> "true");
        registry.add("fitpilot.operations.token", () -> OPERATIONS_TOKEN);
        registry.add("fitpilot.rag.elasticsearch.url", () -> "http://" + ELASTICSEARCH.getHttpHostAddress());
        registry.add("fitpilot.rag.elasticsearch.index", () -> "fitpilot-rag-it");
        registry.add("fitpilot.rag.indexing.fixed-delay-ms", () -> "250");
        registry.add("fitpilot.rag.chunking.parent-max-chars", () -> "500");
        registry.add("fitpilot.rag.chunking.child-max-chars", () -> "180");
        registry.add("fitpilot.rag.chunking.child-overlap-chars", () -> "30");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired KnowledgeRepository knowledge;
    @Autowired RagGovernanceRepository governance;
    @Autowired ElasticsearchKnowledgeIndex lexical;
    @Autowired KnowledgeIngestionService ingestion;
    @Autowired HybridRetrievalService retrieval;
    @Autowired EmbeddingProvider embeddings;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void ingestsSearchesReindexesAndDeletesKnowledgeWithCitations() throws Exception {
        String token = registerAndLogin("rag_user");
        JsonNode ingested = call(post("/api/v1/operations/rag/documents")
                .header("X-Operations-Token", OPERATIONS_TOKEN).contentType("application/json")
                .content(json.writeValueAsBytes(Map.of(
                        "externalId", "fitpilot:rpe-guide", "title", "RPE 与 RIR 训练指南",
                        "category", "training-theory", "sourceUrl", "https://example.org/fitness/rpe-guide",
                        "sourceLicense", "CC-BY-4.0", "publisher", "FitPilot Research",
                        "trustLevel", "PROFESSIONAL", "format", "MARKDOWN", "content", """
                        # RPE 与 RIR
                        RPE 是主观用力程度。RPE 8 通常表示这一组结束时还保留大约两次重复，也就是 RIR 2。
                        训练者应优先维持动作技术，再根据当天状态小幅调整负重。

                        # 减量周
                        Deload 减量周可通过降低训练组数、负重或接近力竭程度来管理累积疲劳，并促进恢复。
                        """))), 201);
        String documentId = ingested.path("data").path("id").asText();
        assertThat(ingested.path("data").path("indexStatus").asText()).isEqualTo("INDEXED");
        assertThat(ingested.path("data").path("parentChunks").asInt()).isEqualTo(2);
        assertThat(ingested.path("data").path("childChunks").asInt()).isGreaterThanOrEqualTo(2);

        JsonNode result = call(get("/api/v1/rag/search")
                .header("Authorization", bearer(token)).param("q", "RPE 8 还剩多少次重复")
                .param("category", "training-theory").param("topK", "3"), 200);
        assertThat(result.path("data").path("retrievalMode").asText()).isEqualTo("HYBRID_RRF");
        assertThat(result.path("data").path("evidenceDecision").asText()).isEqualTo("SUPPORTED");
        assertThat(result.path("data").path("evidenceReason").asText()).isEqualTo("EVIDENCE_FOUND");
        String retrievalId = result.path("data").path("retrievalId").asText();
        assertThat(retrievalId).isNotBlank();
        JsonNode first = result.path("data").path("contexts").get(0);
        assertThat(first.path("content").asText()).contains("RIR 2");
        assertThat(first.path("citation").path("sourceLicense").asText()).isEqualTo("CC-BY-4.0");
        assertThat(first.path("citation").path("trustLevel").asText()).isEqualTo("PROFESSIONAL");
        assertThat(first.path("citation").path("documentVersion").asInt()).isEqualTo(1);
        assertThat(first.path("matchedBy")).extracting(JsonNode::asText).contains("BM25", "VECTOR");

        JsonNode feedback = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                "/api/v1/rag/retrievals/{id}/feedback", retrievalId).header("Authorization", bearer(token))
                .contentType("application/json").content(json.writeValueAsBytes(Map.of(
                        "targetType", "CITATION", "targetKey", documentId, "rating", "NOT_HELPFUL",
                        "reason", "WRONG_CITATION"))), 200);
        String feedbackId = feedback.path("data").path("id").asText();
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                "/api/v1/operations/rag/feedback/{id}/review", feedbackId)
                .header("X-Operations-Token", OPERATIONS_TOKEN).contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("decision", "APPROVED", "reviewer", "rag-it",
                        "correctSourceUrls", java.util.List.of("https://example.org/fitness/rpe-guide")))), 200);
        JsonNode summary = call(get("/api/v1/operations/rag/feedback/summary")
                .header("X-Operations-Token", OPERATIONS_TOKEN), 200);
        assertThat(summary.path("data").path("approvedForEvaluation").asInt()).isEqualTo(1);

        JsonNode evalStarted=call(post("/api/v1/operations/evaluations/rag/runs")
                .header("X-Operations-Token",OPERATIONS_TOKEN),202);
        String evalRunId=evalStarted.path("data").path("runId").asText();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(()->{
            JsonNode run=call(get("/api/v1/operations/evaluations/runs/"+evalRunId)
                    .header("X-Operations-Token",OPERATIONS_TOKEN),200).path("data");
            assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(run.path("totalCases").asInt()).isGreaterThanOrEqualTo(51);
            assertThat(run.path("metrics").path("recallAt5").asDouble()).isGreaterThanOrEqualTo(0.85);
            assertThat(run.path("metrics").path("mrr").asDouble()).isGreaterThanOrEqualTo(0.75);
            assertThat(run.path("metrics").path("citationValidity").asDouble()).isEqualTo(1.0);
        });
        assertThat(jdbc.queryForObject("""
                SELECT recall_at_5 FROM rag_eval_result WHERE run_id=? AND case_id LIKE 'dynamic-%'
                """, Double.class, UUID.fromString(evalRunId))).isEqualTo(1.0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rag_retrieval WHERE user_id IS NULL", Long.class)).isZero();

        JsonNode experimentStarted = call(post("/api/v1/operations/evaluations/rag/feedback-experiments")
                .header("X-Operations-Token", OPERATIONS_TOKEN), 202);
        String experimentRunId = experimentStarted.path("data").path("runId").asText();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
            JsonNode run = call(get("/api/v1/operations/evaluations/runs/" + experimentRunId)
                    .header("X-Operations-Token", OPERATIONS_TOKEN), 200).path("data");
            assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(run.path("totalCases").asInt()).isEqualTo(7);
            JsonNode report = run.path("experimentReport");
            assertThat(report.path("source").asText()).isEqualTo("APPROVED_NEGATIVE_FEEDBACK");
            assertThat(report.path("recommendedProfile").asText()).isNotEqualTo("NONE");
            assertThat(report.path("profiles").size()).isEqualTo(7);
            JsonNode baseline = report.path("profiles").get(0);
            assertThat(baseline.path("categories").path("training-theory")
                    .path("recallAt5").asDouble()).isEqualTo(1);
            assertThat(baseline.path("categories").path("training-theory")
                    .path("mrr").asDouble()).isGreaterThan(0);
            assertThat(baseline.path("categories").path("training-theory")
                    .path("citationValidity").asDouble()).isEqualTo(1);
        });
        awaitDeletionQueueDrained();

        mvc.perform(get("/api/v1/operations/rag/documents").header("X-Operations-Token", "wrong"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1002));
        call(post("/api/v1/operations/rag/documents/{id}/reindex", documentId)
                .header("X-Operations-Token", OPERATIONS_TOKEN), 200);
        JsonNode updated = call(post("/api/v1/operations/rag/documents")
                .header("X-Operations-Token", OPERATIONS_TOKEN).contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("externalId", "fitpilot:rpe-guide", "title", "RPE 指南 v2",
                        "category", "training-theory", "sourceUrl", "https://example.org/fitness/rpe-guide",
                        "sourceLicense", "CC-BY-4.0", "format", "TEXT", "content", "RPE 8 约等于 RIR 2。"))), 201);
        assertThat(updated.path("data").path("version").asInt()).isEqualTo(2);
        JsonNode revisions = call(get("/api/v1/operations/rag/documents/{id}/revisions", documentId)
                .header("X-Operations-Token", OPERATIONS_TOKEN), 200);
        assertThat(revisions.path("data").size()).isEqualTo(2);
        JsonNode restored = call(post("/api/v1/operations/rag/documents/{id}/revisions/1/restore", documentId)
                .header("X-Operations-Token", OPERATIONS_TOKEN), 200);
        assertThat(restored.path("data").path("version").asInt()).isEqualTo(3);
        call(delete("/api/v1/operations/rag/documents/{id}", documentId)
                .header("X-Operations-Token", OPERATIONS_TOKEN), 200);
        JsonNode empty = call(get("/api/v1/rag/search").header("Authorization", bearer(token))
                .param("q", "RPE 8"), 200);
        assertThat(empty.path("data").path("retrievalMode").asText()).isEqualTo("HYBRID_RRF");
        assertThat(empty.path("data").path("contexts")).isEmpty();
        assertThat(empty.path("data").path("evidenceDecision").asText()).isEqualTo("ABSTAINED");
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode deletion = call(get("/api/v1/operations/rag/documents/{id}/delete-status", documentId)
                    .header("X-Operations-Token", OPERATIONS_TOKEN), 200);
            assertThat(deletion.path("data").path("lifecycleStatus").asText()).isEqualTo("DELETED");
            assertThat(deletion.path("data").path("taskStatus").asText()).isEqualTo("SUCCEEDED");
        });
    }

    @Test
    void liveAndSimultaneousEvaluationCorporaAreIsolatedInBothChannels() throws Exception {
        String query = "scopeisolationfixture";
        String category = "scope-isolation";
        var request = new RagDtos.IngestDocumentRequest("scope:same-external-id", query, category,
                "https://example.org/scope", "CC-BY-4.0", "TEXT", query, Map.of(), null, null, null, null);
        RagCorpus runA = RagCorpus.evaluation();
        RagCorpus runB = RagCorpus.evaluation();
        var live = ingestion.ingest(request);
        var evaluatedA = ingestion.ingest(request, null, runA);
        var evaluatedB = ingestion.ingest(request, null, runB);
        assertThat(List.of(live.id(), evaluatedA.id(), evaluatedB.id())).doesNotHaveDuplicates();
        for (var scope : Map.of(RagCorpus.LIVE, live, runA, evaluatedA, runB, evaluatedB).entrySet()) {
            RagCorpus corpus = scope.getKey();
            assertThat(scope.getValue().indexStatus()).isEqualTo("INDEXED");
            UUID child = jdbc.queryForObject("SELECT id FROM knowledge_chunk WHERE document_id=? AND chunk_type='CHILD'",
                    UUID.class, scope.getValue().id());
            assertThat(lexical.search(query, null, 10, corpus)).extracting(SearchHit::chunkId).containsExactly(child);
            assertThat(knowledge.vectorSearch(embeddings.embed(query), category, 10, corpus))
                    .extracting(SearchHit::chunkId).containsExactly(child);
            assertThat(knowledge.contexts(List.of(child), corpus.equals(RagCorpus.LIVE) ? runA : RagCorpus.LIVE))
                    .isEmpty();
        }
        assertThat(retrieval.searchOffline(query, 5, null, null, runB).contexts())
                .extracting(RagDtos.RetrievedContext::documentId).containsExactly(evaluatedB.id());
        assertThat(governance.evaluationCorpus(List.of(), List.of(live.sourceUrl()), 20))
                .extracting(RagGovernanceRepository.EvaluationDocumentRef::documentId).containsExactly(live.id());
        assertThat(ingestion.list(100)).extracting(RagDtos.DocumentView::id).contains(live.id())
                .doesNotContain(evaluatedA.id(), evaluatedB.id());
        assertThatThrownBy(() -> ingestion.delete(evaluatedA.id(), runB)).hasMessage("knowledge document not found");
        ingestion.delete(evaluatedA.id(), runA);

        // Run B is still present, as it would be if an evaluation worker exited before cleanup.
        String token = registerAndLogin("rag_scope_user");
        JsonNode online = call(get("/api/v1/rag/search").header("Authorization", bearer(token)).param("q", query), 200);
        assertThat(online.path("data").path("contexts")).extracting(item -> item.path("documentId").asText())
                .containsExactly(live.id().toString());
    }

    @Test
    void lateVersionCannotOverwriteNewChunksOrRestoreRemovedSlots() throws Exception {
        IndexClaim first = claimVersion("version-fence:replace", "retiredfixture", 3);
        UUID legacyChunk = addLegacyChunk(first.document());
        assertThat(legacyChunkStatus(legacyChunk)).isEqualTo(200);
        assertThat(lexical.search("legacyfixture", VERSION_TEST_CATEGORY, 10, RagCorpus.LIVE)).isEmpty();
        lexical.publish(first);
        assertThat(knowledge.markIndexed(first)).isTrue();
        assertThat(legacyChunkStatus(legacyChunk)).isEqualTo(404);

        IndexClaim stale = knowledge.claimIndexing(first.document(), true).orElseThrow();
        IndexClaim current = claimVersion("version-fence:replace", "currentfixture", 1);
        assertThat(current.slotCount()).isEqualTo(3);
        assertThat(stale.chunks()).hasSize(3).allMatch(chunk -> chunk.version() == 1);
        assertThat(knowledge.claimIndexing(first.document(), true)).isEmpty();
        assertThat(knowledge.markIndexed(stale)).isFalse();
        assertThat(knowledge.markFailed(stale, new IllegalStateException("late failure"))).isFalse();
        assertThat(knowledge.findDocument(current.document().id(), RagCorpus.LIVE).orElseThrow().indexStatus())
                .isEqualTo("INDEXING");

        lexical.publish(current);
        assertThat(knowledge.markIndexed(current)).isTrue();
        assertThatThrownBy(() -> lexical.publish(stale)).hasMessageContaining("item errors");
        assertThat(knowledge.markIndexed(stale)).isFalse();
        assertThat(lexical.search("retiredfixture", VERSION_TEST_CATEGORY, 10, RagCorpus.LIVE)).isEmpty();
        assertThat(lexical.search("currentfixture", VERSION_TEST_CATEGORY, 10, RagCorpus.LIVE))
                .extracting(SearchHit::chunkId).containsExactly(current.chunks().getFirst().chunkId());
    }

    @Test
    void abandonedClaimCannotFinishEvenWhenTheDocumentVersionIsUnchanged() {
        IndexClaim abandoned = claimVersion("version-fence:reclaim", "reclaimfixture", 1);
        IndexClaim recovered = new TransactionTemplate(transactions).execute(status -> {
            jdbc.update("UPDATE knowledge_document SET updated_at=now()-interval '6 minutes' WHERE id=?",
                    abandoned.document().id());
            return knowledge.claimIndexing(abandoned.document(), false).orElseThrow();
        });

        assertThat(recovered.token()).isNotEqualTo(abandoned.token());
        assertThat(knowledge.markIndexed(abandoned)).isFalse();
        assertThat(knowledge.markFailed(abandoned, new IllegalStateException("lost claim"))).isFalse();
        lexical.publish(recovered);
        assertThat(knowledge.markIndexed(recovered)).isTrue();
    }

    @Test
    void delayedIndexAndDeletionAreFencedAcrossDeletionAndRestore() {
        awaitDeletionQueueDrained();
        IndexClaim first = claimVersion("version-fence:restore", "deletedfixture", 2);
        lexical.publish(first);
        assertThat(knowledge.markIndexed(first)).isTrue();
        IndexClaim stale = knowledge.claimIndexing(first.document(), true).orElseThrow();
        RagGovernanceRepository.DeletionTask deletion = new TransactionTemplate(transactions).execute(status -> {
            assertThat(knowledge.requestDelete(first.document().id(), RagCorpus.LIVE)).isTrue();
            return governance.claimDeletionTasks(1).getFirst();
        });

        assertThat(deletion.document()).isEqualTo(first.document());
        lexical.deleteDocument(deletion.document(), deletion.slotCount());
        assertThatThrownBy(() -> lexical.publish(stale)).hasMessageContaining("item errors");
        assertThat(knowledge.markIndexed(stale)).isFalse();
        assertThat(lexical.search("deletedfixture", VERSION_TEST_CATEGORY, 10, RagCorpus.LIVE)).isEmpty();

        IndexClaim restored = claimVersion("version-fence:restore", "restoredfixture", 1);
        lexical.publish(restored);
        assertThat(knowledge.markIndexed(restored)).isTrue();
        assertThatThrownBy(() -> lexical.deleteDocument(deletion.document(), deletion.slotCount()))
                .hasMessageContaining("item errors");
        assertThat(governance.deletionSucceeded(deletion)).isFalse();
        assertThat(governance.deletionFailed(deletion, new IllegalStateException("late deletion"))).isFalse();
        assertThat(knowledge.findDocument(restored.document().id(), RagCorpus.LIVE).orElseThrow().indexStatus())
                .isEqualTo("INDEXED");
        assertThat(lexical.search("restoredfixture", VERSION_TEST_CATEGORY, 10, RagCorpus.LIVE))
                .extracting(SearchHit::chunkId).containsExactly(restored.chunks().getFirst().chunkId());
    }

    private void awaitDeletionQueueDrained() {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(45)).untilAsserted(() ->
                assertThat(jdbc.queryForList("""
                        SELECT status FROM rag_deletion_task WHERE status IN ('PENDING', 'RUNNING', 'FAILED')
                        """, String.class)).as("unfinished RAG deletion tasks").isEmpty());
    }

    private IndexClaim claimVersion(String externalId, String text, int childCount) {
        var write = new KnowledgeRepository.DocumentWrite(UUID.randomUUID(), externalId, text, VERSION_TEST_CATEGORY,
                "https://example.org/synthetic", "CC-BY-4.0", "TEXT", "0".repeat(64), text, Map.of(), null,
                "COMMUNITY", null, null);
        UUID parent = UUID.randomUUID();
        var chunks = new ArrayList<StoredChunk>();
        chunks.add(new StoredChunk(parent, null, "PARENT", 0, null, text, text, null));
        for (int ordinal = 0; ordinal < childCount; ordinal++) {
            chunks.add(new StoredChunk(UUID.randomUUID(), parent, "CHILD", ordinal, null, text, text, null));
        }
        return new TransactionTemplate(transactions).execute(status ->
                knowledge.claimIndexing(knowledge.replace(write, chunks, RagCorpus.LIVE), false).orElseThrow());
    }

    private UUID addLegacyChunk(DocumentVersion document) throws Exception {
        lexical.ensureIndex();
        UUID id = UUID.randomUUID();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + ELASTICSEARCH.getHttpHostAddress()
                        + "/fitpilot-rag-it/_doc/" + id + "?refresh=wait_for"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                        "documentId", document.id().toString(), "documentVersion", document.version(),
                        "category", VERSION_TEST_CATEGORY, "lexicalText", "legacyfixture", "lifecycleStatus", "ACTIVE"))))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertThat(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(201);
        }
        return id;
    }

    private int legacyChunkStatus(UUID id) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + ELASTICSEARCH.getHttpHostAddress()
                + "/fitpilot-rag-it/_doc/" + id)).timeout(Duration.ofSeconds(10)).GET().build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    private String registerAndLogin(String username) throws Exception {
        call(post("/api/v1/auth/register").contentType("application/json").content(json.writeValueAsBytes(
                Map.of("username", username, "email", username + "@example.com", "password", "password123"))), 201);
        return call(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsBytes(
                Map.of("username", username, "password", "password123"))), 200)
                .path("data").path("accessToken").asText();
    }

    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                          int expectedStatus) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsByteArray());
    }

    private String bearer(String token) { return "Bearer " + token; }
}
