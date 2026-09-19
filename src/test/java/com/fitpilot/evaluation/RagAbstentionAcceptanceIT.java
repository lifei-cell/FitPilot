package com.fitpilot.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.rag.application.HybridRetrievalService;
import com.fitpilot.rag.application.KnowledgeIngestionService;
import com.fitpilot.rag.domain.RagTuningProfile;
import com.fitpilot.rag.dto.RagDtos;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance gate for the independent hard-negative set. The test uses real
 * PostgreSQL/pgvector and Elasticsearch containers; no model-generated labels
 * are used to decide whether a citation is allowed out.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RagAbstentionAcceptanceIT {
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
        registry.add("security.jwt.secret", () -> "rag-abstention-acceptance-secret-32-bytes");
        registry.add("fitpilot.operations.token", () -> "rag-abstention-acceptance-token");
        registry.add("fitpilot.events.enabled", () -> "false");
        registry.add("fitpilot.llm.enabled", () -> "false");
        registry.add("fitpilot.rag.embedding.provider", () -> "LOCAL");
        registry.add("fitpilot.rag.elasticsearch.url", () -> "http://" + ELASTICSEARCH.getHttpHostAddress());
        registry.add("fitpilot.rag.elasticsearch.index", () -> "rag-abstention-acceptance");
        registry.add("fitpilot.rag.evidence-gate-enabled", () -> "true");
    }

    @Autowired ObjectMapper json;
    @Autowired KnowledgeIngestionService ingestion;
    @Autowired HybridRetrievalService retrieval;

    @Test
    void independentHardNegativeV2ProducesAtMostOnePotentialCitation() throws Exception {
        ingestCorpus();
        List<HardNegative> cases = readJson("/retrieval/hard-negatives-v2.json",
                new TypeReference<>() {});
        long returned = cases.stream()
                .map(item -> retrieval.searchOffline(item.query(), 5, "EVALUATION", hybrid()))
                .filter(response -> !response.contexts().isEmpty())
                .count();

        assertThat(cases).hasSize(20);
        assertThat(returned).isLessThanOrEqualTo(1);
    }

    @Test
    void answerableQueriesRetainRecallAtThreeAcrossAllRetrievalPaths() throws Exception {
        ingestCorpus();
        List<AnswerCase> cases = readJsonLines("/eval/rag-v1.jsonl", AnswerCase.class);
        assertThat(cases).hasSize(50);
        for (RagTuningProfile profile : profiles()) {
            long hits = 0;
            for (AnswerCase item : cases) {
                var response = retrieval.searchOffline(item.query(), 5, "EVALUATION", profile);
                if (response.contexts().stream().limit(3)
                        .anyMatch(context -> context.citation().sourceUrl().equals(itemSource(item)))) hits++;
            }
            assertThat(hits).as(profile.id() + " Recall@3").isEqualTo(cases.size());
        }
    }

    private void ingestCorpus() throws Exception {
        for (RagDtos.IngestDocumentRequest document : readJsonLines("/eval/rag-corpus-v1.jsonl",
                RagDtos.IngestDocumentRequest.class)) {
            ingestion.ingest(document);
        }
    }

    private List<RagTuningProfile> profiles() {
        return List.of(
                new RagTuningProfile("bm25-only", 2400, 700, 100, 60, 1, 0, "NONE"),
                new RagTuningProfile("vector-only", 2400, 700, 100, 60, 0, 1, "NONE"),
                hybrid());
    }

    private RagTuningProfile hybrid() {
        return new RagTuningProfile("hybrid-rrf", 2400, 700, 100, 60, 1, 1, "NONE");
    }

    private String itemSource(AnswerCase item) {
        return item.expectedSourceUrls().getFirst();
    }

    private <T> List<T> readJsonLines(String path, Class<T> type) throws Exception {
        try (var reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().filter(line -> !line.isBlank()).map(line -> read(line, type)).toList();
        }
    }

    private <T> List<T> readJson(String path, TypeReference<List<T>> type) throws Exception {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return json.readValue(input, type);
        }
    }

    private <T> T read(String line, Class<T> type) {
        try { return json.readValue(line, type); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    record HardNegative(String id, String topic, String query, String missingFact) {}
    record AnswerCase(String id, String query, List<String> expectedTerms, List<String> expectedSourceUrls,
                      String category) {}
}
