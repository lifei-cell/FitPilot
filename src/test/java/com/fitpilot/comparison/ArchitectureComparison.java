package com.fitpilot.comparison;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.evaluation.application.EvaluationDatasetLoader;
import com.fitpilot.infrastructure.events.*;
import com.fitpilot.infrastructure.performance.DistributedLockService;
import com.fitpilot.infrastructure.performance.PerformanceProperties;
import com.fitpilot.observability.FitPilotMetrics;
import com.fitpilot.rag.application.HybridRetrievalService;
import com.fitpilot.rag.application.KnowledgeIngestionService;
import com.fitpilot.rag.config.RagProperties;
import com.fitpilot.workout.application.WorkoutService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** Not named *Test/*IT: run explicitly with scripts/run-architecture-comparison.ps1. */
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ArchitectureComparison {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");
    @Container static final ElasticsearchContainer ES = new ElasticsearchContainer(
            "docker.elastic.co/elasticsearch/elasticsearch:8.17.4").withEnv("xpack.security.enabled", "false");

    @BeforeAll static void recordContainers() throws Exception {
        var images = new java.util.LinkedHashMap<String, Object>();
        for (var container : java.util.List.of(PG, REDIS, KAFKA, ES)) {
            var inspected = container.getDockerClient().inspectImageCmd(container.getDockerImageName()).exec();
            images.put(container.getDockerImageName(), java.util.Map.of("imageId", inspected.getId(),
                    "repoDigests", inspected.getRepoDigests() == null ? java.util.List.of() : inspected.getRepoDigests()));
        }
        ComparisonEvidence.write("containers", images);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("security.jwt.secret", () -> "comparison-only-secret-with-at-least-32-bytes");
        registry.add("fitpilot.operations.token", () -> "comparison-only-operations");
        registry.add("fitpilot.events.enabled", () -> "false"); // Dispatch is owned by the experiment, never a scheduler.
        registry.add("fitpilot.llm.enabled", () -> "false");
        registry.add("fitpilot.rag.embedding.provider", () -> "local");
        registry.add("fitpilot.rag.elasticsearch.url", () -> "http://" + ES.getHttpHostAddress());
        registry.add("fitpilot.rag.elasticsearch.index", () -> "architecture-comparison");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper json;
    @Autowired DistributedLockService locks;
    @Autowired PerformanceProperties performance;
    @Autowired WorkoutService workouts;
    @Autowired PlatformTransactionManager transactions;
    @Autowired OutboxRepository outbox;
    @Autowired WorkoutCompletedEventHandler workoutHandler;
    @Autowired PersonalRecordEventHandler recordHandler;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired FitPilotMetrics metrics;
    @Autowired HybridRetrievalService retrieval;
    @Autowired KnowledgeIngestionService ingestion;
    @Autowired EvaluationDatasetLoader datasets;
    @Autowired RagProperties rag;

    @Test void cacheAlternatives() throws Exception {
        new CacheComparison(jdbc, redis, json, locks, performance).run();
    }

    @Test void eventAlternatives() throws Exception {
        new EventComparison(jdbc, workouts, transactions, outbox, workoutHandler, recordHandler,
                new OutboxRelay(outbox, kafka, new EventProperties(), metrics), KAFKA.getBootstrapServers()).run();
    }

    @Test void retrievalAlternatives() throws Exception {
        new RetrievalComparison(retrieval, ingestion, datasets, rag).run();
    }
}
