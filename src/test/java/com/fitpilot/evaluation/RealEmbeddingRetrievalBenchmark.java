package com.fitpilot.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.evaluation.application.EvaluationDatasetLoader;
import com.fitpilot.rag.application.HybridRetrievalService;
import com.fitpilot.rag.application.KnowledgeIngestionService;
import com.fitpilot.rag.config.RagProperties;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicitly run by scripts/run-real-retrieval.ps1 against a local learned embedding model. */
@SpringBootTest
@Testcontainers
class RealEmbeddingRetrievalBenchmark {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);
    @Container static final ElasticsearchContainer ES = new ElasticsearchContainer(
            "docker.elastic.co/elasticsearch/elasticsearch:8.17.4")
            .withEnv("xpack.security.enabled", "false");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("security.jwt.secret", () -> "real-retrieval-benchmark-secret-32-bytes");
        registry.add("fitpilot.operations.token", () -> "real-retrieval-benchmark-only");
        registry.add("fitpilot.events.enabled", () -> "false");
        registry.add("fitpilot.llm.enabled", () -> "false");
        registry.add("fitpilot.rag.embedding.provider", () -> "OPENAI_COMPATIBLE");
        registry.add("fitpilot.rag.embedding.api-url", () -> "http://127.0.0.1:"
                + System.getProperty("fitpilot.real-embedding.port", "18763") + "/v1/embeddings");
        registry.add("fitpilot.rag.embedding.api-key", () -> "local-evaluation-only");
        registry.add("fitpilot.rag.embedding.model", () -> "paraphrase-multilingual-MiniLM-L12-v2-onnx-int8");
        registry.add("fitpilot.rag.elasticsearch.url", () -> "http://" + ES.getHttpHostAddress());
        registry.add("fitpilot.rag.elasticsearch.index", () -> "real-retrieval-evaluation");
    }

    @Autowired HybridRetrievalService retrieval;
    @Autowired KnowledgeIngestionService ingestion;
    @Autowired EvaluationDatasetLoader datasets;
    @Autowired RagProperties rag;
    @Autowired ObjectMapper json;

    @Test void compareFrozenHoldout() throws Exception {
        String output = System.getProperty("fitpilot.real-retrieval.output");
        assertThat(output).as("The runner must supply an evidence path").isNotBlank();
        long indexingStarted = System.nanoTime();
        int childChunks = 0;
        for (var document : datasets.ragCorpus()) {
            var indexed = ingestion.ingest(document);
            assertThat(indexed.indexStatus()).isEqualTo("INDEXED");
            childChunks += indexed.childChunks();
        }
        double indexingMs = elapsed(indexingStarted);
        HoldoutCase[] cases;
        try (var input = new ClassPathResource("retrieval/holdout-v1.json").getInputStream()) {
            cases = json.readValue(input, HoldoutCase[].class);
        }
        assertThat(cases).hasSize(28);
        var chunk = rag.getChunking();
        int rrfK = rag.getRetrieval().getRrfK();
        var profiles = List.of(
                new RagTuningProfile("bm25-only", chunk.getParentMaxChars(), chunk.getChildMaxChars(),
                        chunk.getChildOverlapChars(), rrfK, 1, 0, "NONE"),
                new RagTuningProfile("vector-only", chunk.getParentMaxChars(), chunk.getChildMaxChars(),
                        chunk.getChildOverlapChars(), rrfK, 0, 1, "NONE"),
                new RagTuningProfile("hybrid-rrf", chunk.getParentMaxChars(), chunk.getChildMaxChars(),
                        chunk.getChildOverlapChars(), rrfK, 1, 1, "NONE"));
        for (var profile : profiles) for (var item : cases) {
            checkMode(retrieval.searchOffline(item.query(), 5, "EVALUATION", profile), profile);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int round = 1; round <= 3; round++) {
            for (int offset = 0; offset < profiles.size(); offset++) {
                var profile = profiles.get((round - 1 + offset) % profiles.size());
                for (var item : cases) {
                    long started = System.nanoTime();
                    var result = retrieval.searchOffline(item.query(), 5, "EVALUATION", profile);
                    double latencyMs = elapsed(started);
                    checkMode(result, profile);
                    var sources = result.contexts().stream().map(context -> Map.of(
                            "source", context.citation().sourceUrl(),
                            "supportsLabel", !item.source().isBlank()
                                    && context.citation().sourceUrl().endsWith("/" + item.source())
                                    && context.content().contains(item.evidence()),
                            "matchedBy", context.matchedBy())).toList();
                    int rank = -1;
                    for (int i = 0; i < sources.size(); i++) {
                        if (sources.get(i).get("source").equals("https://fitpilot.local/eval/" + item.source())) {
                            rank = i + 1;
                            break;
                        }
                    }
                    var row = new LinkedHashMap<String, Object>();
                    row.put("round", round); row.put("profile", profile.id()); row.put("case", item.id());
                    row.put("category", item.category()); row.put("expectedSource", item.source());
                    row.put("rank", rank); row.put("top1Supported", !sources.isEmpty()
                            && Boolean.TRUE.equals(sources.getFirst().get("supportsLabel")));
                    row.put("candidateCount", result.candidateCount()); row.put("latencyMs", latencyMs);
                    row.put("sources", sources);
                    rows.add(row);
                }
            }
        }
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("documents", datasets.ragCorpus().size());
        evidence.put("childChunks", childChunks); evidence.put("indexingMs", indexingMs);
        evidence.put("queries", cases.length); evidence.put("rounds", 3);
        evidence.put("profiles", profiles); evidence.put("rows", rows);
        Path path = Path.of(output).toAbsolutePath();
        Files.createDirectories(path.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), evidence);
    }

    private static void checkMode(RagDtos.SearchResponse response, RagTuningProfile profile) {
        String expected = switch (profile.id()) {
            case "bm25-only" -> "BM25_ONLY";
            case "vector-only" -> "VECTOR_ONLY";
            default -> "HYBRID_RRF";
        };
        assertThat(response.retrievalMode()).isEqualTo(expected);
        assertThat(response.embeddingProvider()).contains("MiniLM");
    }

    private static double elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000.0;
    }

    record HoldoutCase(String id, String category, String query, String source, String evidence) {}
}
