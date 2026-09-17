package com.fitpilot.infrastructure.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.observability.FitPilotMetrics;
import com.fitpilot.workout.application.WorkoutService;
import com.fitpilot.workout.dto.WorkoutDtos;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Explicit isolated experiment; run with -Dit.test=OutboxBacklogBenchmark. */
@SpringBootTest(properties = {
        "fitpilot.events.enabled=false", "fitpilot.rag.enabled=false", "fitpilot.llm.enabled=false",
        "fitpilot.agent.enabled=false", "fitpilot.performance.cache.enabled=false",
        "fitpilot.performance.rate-limit.enabled=false",
        "spring.kafka.producer.properties.max.block.ms=3000",
        "spring.kafka.producer.properties.request.timeout.ms=1000",
        "spring.kafka.producer.properties.delivery.timeout.ms=3000"
})
@Testcontainers(disabledWithoutDocker = true)
class OutboxBacklogBenchmark {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    @Container static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("security.jwt.secret", () -> "outbox-backlog-test-secret-at-least-32-bytes");
        registry.add("fitpilot.operations.token", () -> "outbox-backlog-test-operations");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired WorkoutService workouts;
    @Autowired OutboxRepository repository;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaAdmin kafkaAdmin;
    @Autowired WorkoutCompletedEventHandler workoutHandler;
    @Autowired PersonalRecordEventHandler recordHandler;
    @Autowired FitPilotMetrics metrics;
    @Autowired ObjectMapper json;

    @Test
    void measureBrokerOutageReclaimAndAgedBacklog() throws Exception {
        createTopics();
        // Normal startup resolves the cluster ID before an outage; the first run also captured cold-start behavior.
        assertThat(kafkaAdmin.clusterId()).isNotBlank();
        int batchSize = Integer.getInteger("fitpilot.backlog.batch-size", 100);
        var relayA = relay(batchSize);
        var relayB = relay(batchSize);
        List<Map<String, Object>> scenarios = new ArrayList<>();
        try (var pr = consumer(EventTopics.WORKOUT_COMPLETED);
             var analytics = consumer(EventTopics.WORKOUT_COMPLETED);
             var notifications = consumer(EventTopics.PERSONAL_RECORD_CREATED)) {
            scenarios.add(outage(relayA, relayB, pr, analytics, notifications));
            scenarios.add(reclaim(relayB, pr, analytics, notifications));
            scenarios.add(agedBacklog(relayA, relayB, pr, analytics, notifications));
        }
        Path output = Path.of(System.getenv().getOrDefault("FITPILOT_OUTBOX_OUTPUT", "target/outbox-backlog/run"));
        Files.createDirectories(output);
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("results.json").toFile(), Map.of(
                "finishedAt", Instant.now().toString(), "batchSize", batchSize, "scenarios", scenarios,
                "scope", "service writes and real PostgreSQL/Kafka Testcontainers; manual production handlers; synthetic aged backlog"));
    }

    private Map<String, Object> outage(OutboxRelay relayA, OutboxRelay relayB,
                                       KafkaConsumer<String, String> pr, KafkaConsumer<String, String> analytics,
                                       KafkaConsumer<String, String> notifications) throws Exception {
        var seed = seed("outage");
        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
        long start = System.nanoTime();
        try {
            complete(seed);
            assertThat(status(seed.workoutId())).isEqualTo("COMPLETED");
            assertThat(eventStatus(seed.workoutId())).isEqualTo("PENDING");
            relayA.relay();
            assertThat(eventStatus(seed.workoutId())).isEqualTo("PENDING");
            assertThat(count("SELECT count(*) FROM personal_record WHERE workout_id=?", seed.workoutId())).isZero();
        } finally {
            KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }
        double maxAge = oldestAgeMs();
        long recoveredAt = System.nanoTime();
        drain(1, relayA, relayB, pr, analytics, notifications, Duration.ofSeconds(40));
        assertProjection(seed);
        return Map.of("name", "broker-outage", "completedWhileBrokerPaused", 1,
                "outageMs", millis(start), "maximumObservedOldestAgeMs", maxAge,
                "recoveryMs", millis(recoveredAt), "duplicateBusinessRows", duplicateBusinessRows());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> reclaim(OutboxRelay relay,
                                        KafkaConsumer<String, String> pr, KafkaConsumer<String, String> analytics,
                                        KafkaConsumer<String, String> notifications) throws Exception {
        var seed = seed("reclaim");
        complete(seed);
        var delayedKafka = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
        var acknowledgement = new CompletableFuture<SendResult<String, String>>();
        var sentByA = new CountDownLatch(1);
        when(delayedKafka.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            kafka.send(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2))
                    .get(5, TimeUnit.SECONDS);
            sentByA.countDown();
            return acknowledgement;
        });
        var properties = new EventProperties();
        properties.getRelay().setSendTimeoutSeconds(15);
        var relayA = new OutboxRelay(repository, delayedKafka, properties, metrics);
        long recoveredAt;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var runningA = executor.submit(relayA::relay);
            try {
                assertThat(sentByA.await(5, TimeUnit.SECONDS)).isTrue();
                long id = jdbc.queryForObject("SELECT id FROM outbox_event WHERE aggregate_id=? AND event_type='WorkoutCompletedEvent'", Long.class, String.valueOf(seed.workoutId()));
                UUID oldToken = jdbc.queryForObject("SELECT claim_token FROM outbox_event WHERE id=?", UUID.class, id);
                jdbc.update("UPDATE outbox_event SET claimed_at=CURRENT_TIMESTAMP - INTERVAL '60 seconds' WHERE id=?", id);
                recoveredAt = System.nanoTime();
                drain(2, relay, relay, pr, analytics, notifications, Duration.ofSeconds(40));
                acknowledgement.complete(null);
                runningA.get(5, TimeUnit.SECONDS);
                assertThat(repository.markSent(new OutboxEvent(id, EventTopics.WORKOUT_COMPLETED,
                        String.valueOf(seed.workoutId()), "{}", 0, oldToken))).isFalse();
            } finally {
                acknowledgement.completeExceptionally(new IllegalStateException("test cleanup"));
            }
        }
        assertProjection(seed);
        return Map.of("name", "dual-relay-reclaim", "sameEventKafkaPublishes", 2,
                "oldClaimRejected", true, "recoveryMs", millis(recoveredAt),
                "duplicateBusinessRows", duplicateBusinessRows());
    }

    private Map<String, Object> agedBacklog(OutboxRelay relayA, OutboxRelay relayB,
                                             KafkaConsumer<String, String> pr, KafkaConsumer<String, String> analytics,
                                             KafkaConsumer<String, String> notifications) throws Exception {
        List<Seed> seeds = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            var seed = seed("backlog-" + i);
            complete(seed);
            seeds.add(seed);
        }
        jdbc.update("UPDATE outbox_event SET created_at=CURRENT_TIMESTAMP - INTERVAL '120 seconds' WHERE status='PENDING'");
        double maxAge = oldestAgeMs();
        long recoveredAt = System.nanoTime();
        drain(42, relayA, relayB, pr, analytics, notifications, Duration.ofSeconds(90));
        for (var seed : seeds) assertProjection(seed);
        assertThat(count("SELECT count(*) FROM outbox_event WHERE status IN ('PENDING','SENDING','FAILED')")).isZero();
        return Map.of("name", "aged-backlog", "workouts", seeds.size(),
                "maximumObservedOldestAgeMs", maxAge, "ageWasBackdated", true,
                "recoveryMs", millis(recoveredAt), "duplicateBusinessRows", duplicateBusinessRows());
    }

    private void drain(int expectedWorkouts, OutboxRelay a, OutboxRelay b,
                       KafkaConsumer<String, String> pr, KafkaConsumer<String, String> analytics,
                       KafkaConsumer<String, String> notifications, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            a.relay();
            b.relay();
            pr.poll(Duration.ofMillis(50)).forEach(record -> workoutHandler.projectPersonalRecords(record.value()));
            analytics.poll(Duration.ofMillis(50)).forEach(record -> workoutHandler.projectAnalytics(record.value()));
            notifications.poll(Duration.ofMillis(50)).forEach(record -> recordHandler.notifyUser(record.value()));
            if (count("SELECT count(*) FROM workout_analytics_projection") >= expectedWorkouts
                    && count("SELECT count(*) FROM user_notification") >= expectedWorkouts * 4L
                    && count("SELECT count(*) FROM outbox_event WHERE status IN ('PENDING','SENDING')") == 0) return;
        }
        throw new AssertionError("projection did not catch up before " + timeout);
    }

    private OutboxRelay relay(int batchSize) {
        var properties = new EventProperties();
        properties.getRelay().setBatchSize(batchSize);
        properties.getRelay().setSendTimeoutSeconds(2);
        return new OutboxRelay(repository, kafka, properties, metrics);
    }

    private void createTopics() throws Exception {
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(EventTopics.WORKOUT_COMPLETED, 3, (short) 1),
                    new NewTopic(EventTopics.PERSONAL_RECORD_CREATED, 3, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
    }

    private KafkaConsumer<String, String> consumer(String topic) {
        var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "backlog-" + topic + "-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    private Seed seed(String label) {
        long userId = jdbc.queryForObject("INSERT INTO users(username,password_hash) VALUES (?, 'test') RETURNING id",
                Long.class, "outbox-" + label + "-" + UUID.randomUUID().toString().substring(0, 8));
        long workoutId = jdbc.queryForObject("""
                INSERT INTO workout(user_id,name,status,started_at)
                VALUES (?, ?, 'IN_PROGRESS', CURRENT_TIMESTAMP) RETURNING id
                """, Long.class, userId, label);
        long exerciseId = jdbc.queryForObject("""
                INSERT INTO workout_exercise(workout_id,exercise_id,sequence,exercise_name)
                SELECT ?, id, 1, name FROM exercise ORDER BY id LIMIT 1 RETURNING id
                """, Long.class, workoutId);
        workouts.addSet(userId, workoutId, exerciseId,
                new WorkoutDtos.SetRequest(new BigDecimal("80.00"), 5, null, null, false, false));
        return new Seed(userId, workoutId);
    }

    private void complete(Seed seed) { workouts.complete(seed.userId(), seed.workoutId()); }
    private String status(long id) { return jdbc.queryForObject("SELECT status FROM workout WHERE id=?", String.class, id); }
    private String eventStatus(long id) { return jdbc.queryForObject("SELECT status FROM outbox_event WHERE aggregate_id=? AND event_type='WorkoutCompletedEvent'", String.class, String.valueOf(id)); }
    private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private double oldestAgeMs() { return jdbc.queryForObject("SELECT COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP-MIN(created_at)))*1000,0) FROM outbox_event WHERE status IN ('PENDING','SENDING')", BigDecimal.class).doubleValue(); }
    private double millis(long started) { return Math.round((System.nanoTime() - started) / 10000.0) / 100.0; }
    private long duplicateBusinessRows() { return count("SELECT count(*) - count(DISTINCT (workout_id,record_type)) FROM personal_record"); }

    private void assertProjection(Seed seed) {
        assertThat(status(seed.workoutId())).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM personal_record WHERE workout_id=?", seed.workoutId())).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM workout_analytics_projection WHERE workout_id=?", seed.workoutId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM user_notification WHERE user_id=?", seed.userId())).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM outbox_event WHERE aggregate_id=? AND event_type='WorkoutCompletedEvent'", String.valueOf(seed.workoutId()))).isEqualTo(1);
    }

    private record Seed(long userId, long workoutId) {}
}
