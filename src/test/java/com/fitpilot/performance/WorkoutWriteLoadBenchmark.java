package com.fitpilot.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.workout.application.RecoverableSetService;
import com.fitpilot.workout.application.WorkoutService;
import com.fitpilot.workout.dto.WorkoutDtos;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Service-level write-path load test. Run explicitly with scripts/run-workout-write-load.ps1.
 * It keeps the Docker and data set fixed so the baseline and optimized runs are comparable.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkoutWriteLoadBenchmark {
    private static final int DEFAULT_MULTI_USERS = 24;
    private static final int DEFAULT_MULTI_REQUESTS = 384;
    private static final int DEFAULT_SAME_REQUESTS = 192;
    private static final int DEFAULT_OFFLINE_UNIQUE = 96;
    private static final int DEFAULT_OFFLINE_REPLAYS = 2;
    private static final int DEFAULT_CONCURRENCY = 32;
    private static final WorkoutDtos.SetRequest SET = new WorkoutDtos.SetRequest(
            new BigDecimal("50.00"), 8, null, null, false, false);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "16");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "4");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "5000");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("security.jwt.secret", () -> "write-load-test-secret-key-with-32-bytes");
        registry.add("fitpilot.operations.token", () -> "write-load-test-operations");
        registry.add("fitpilot.events.enabled", () -> "false");
        registry.add("fitpilot.rag.enabled", () -> "false");
        registry.add("fitpilot.llm.enabled", () -> "false");
        registry.add("fitpilot.agent.enabled", () -> "false");
        registry.add("fitpilot.performance.cache.enabled", () -> "false");
        registry.add("fitpilot.performance.rate-limit.enabled", () -> "false");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired RecoverableSetService recoverable;
    @Autowired WorkoutService workouts;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;

    @Test
    void runWritePathLoad() throws Exception {
        int concurrency = setting("fitpilot.load.concurrency", DEFAULT_CONCURRENCY);
        int multiUsers = setting("fitpilot.load.multi-users", DEFAULT_MULTI_USERS);
        int multiRequests = setting("fitpilot.load.multi-requests", DEFAULT_MULTI_REQUESTS);
        int sameRequests = setting("fitpilot.load.same-requests", DEFAULT_SAME_REQUESTS);
        int offlineUnique = setting("fitpilot.load.offline-unique", DEFAULT_OFFLINE_UNIQUE);
        int offlineReplays = setting("fitpilot.load.offline-replays", DEFAULT_OFFLINE_REPLAYS);

        List<Map<String, Object>> scenarios = new ArrayList<>();
        scenarios.add(runMultiUser(concurrency, multiUsers, multiRequests));
        scenarios.add(runSameWorkout(concurrency, sameRequests));
        scenarios.add(runOfflineRecovery(concurrency, offlineUnique, offlineReplays));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("finishedAt", Instant.now().toString());
        report.put("postgresImage", POSTGRES.getDockerImageName());
        report.put("redisImage", REDIS.getDockerImageName());
        report.put("postgresJdbcUrl", POSTGRES.getJdbcUrl().replace(POSTGRES.getHost(), "<container>"));
        report.put("hikariMaximumPoolSize", hikari().getMaximumPoolSize());
        report.put("hikariMinimumIdle", hikari().getMinimumIdle());
        report.put("settings", Map.of(
                "concurrency", concurrency,
                "multiUsers", multiUsers,
                "multiRequests", multiRequests,
                "sameRequests", sameRequests,
                "offlineUnique", offlineUnique,
                "offlineReplays", offlineReplays));
        report.put("scenarios", scenarios);

        Path output = Path.of(System.getenv().getOrDefault(
                "FITPILOT_LOAD_OUTPUT", "target/workout-write-load/run"));
        Files.createDirectories(output);
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("results.json").toFile(), report);

        assertThat(scenarios).allSatisfy(scenario -> {
            assertThat((Integer) scenario.get("errors"))
                    .as("errors in " + scenario.get("name")).isZero();
            assertThat((Integer) scenario.get("actualRows"))
                    .as("row count in " + scenario.get("name"))
                    .isEqualTo((Integer) scenario.get("expectedRows"));
        });
        Map<String, Object> offline = scenarios.get(2);
        assertThat((Integer) offline.get("receiptRows")).isEqualTo(offlineUnique);
        assertThat((Integer) offline.get("completionEvents")).isEqualTo(1);
        assertThat((Integer) offline.get("pendingOutbox")).isEqualTo(1);
    }

    private Map<String, Object> runMultiUser(int concurrency, int users, int requests) throws Exception {
        List<Seed> warmup = seedWorkouts("multi-warmup", users);
        List<Operation> warmupOps = operations(warmup, Math.min(requests, users * 2), false);
        runParallel("multi-user-warmup", concurrency, warmupOps, () -> false, false);

        List<Seed> seeds = seedWorkouts("multi-measured", users);
        List<Operation> measured = operations(seeds, requests, false);
        Map<String, Object> result = runParallel("multi-user-add", concurrency, measured,
                () -> false, true);
        result.putAll(counts(seeds));
        result.put("expectedRows", requests);
        return result;
    }

    private Map<String, Object> runSameWorkout(int concurrency, int requests) throws Exception {
        Seed warmup = seedWorkout("same-warmup");
        runParallel("same-workout-warmup", concurrency,
                operations(List.of(warmup), Math.min(requests, 32), false), () -> false, false);

        Seed measuredSeed = seedWorkout("same-measured");
        Map<String, Object> result = runParallel("same-workout-add", concurrency,
                operations(List.of(measuredSeed), requests, false), () -> false, true);
        result.putAll(counts(List.of(measuredSeed)));
        result.put("expectedRows", requests);
        return result;
    }

    private Map<String, Object> runOfflineRecovery(int concurrency, int unique, int replays) throws Exception {
        Seed warmup = seedWorkout("offline-warmup");
        runParallel("offline-warmup", concurrency,
                operations(List.of(warmup), Math.min(unique, 32), true), () -> true, false);

        Seed measuredSeed = seedWorkout("offline-measured");
        List<Operation> initial = operations(List.of(measuredSeed), unique, true);
        List<Operation> measured = new ArrayList<>(initial);
        for (int replay = 0; replay < replays; replay++) measured.addAll(initial);
        Map<String, Object> result = runParallel("offline-recovery", concurrency, measured,
                () -> true, true);

        int beforeEvents = jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class);
        workouts.complete(measuredSeed.userId(), measuredSeed.workoutId());
        int afterEvents = jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class);
        result.putAll(counts(List.of(measuredSeed)));
        result.put("expectedRows", unique);
        result.put("uniqueRequests", unique);
        result.put("replayRequests", unique * replays);
        result.put("completionEvents", afterEvents - beforeEvents);
        result.put("pendingOutbox", jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE status IN ('PENDING','SENDING')", Integer.class));
        return result;
    }

    private Map<String, Object> runParallel(String name, int concurrency, List<Operation> operations,
                                            Supplier<Boolean> recoverableCall, boolean sample) throws Exception {
        int workers = Math.max(1, Math.min(concurrency, operations.size()));
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CallResult>> futures = new ArrayList<>(operations.size());
        for (Operation operation : operations) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("load start timed out");
                long begin = System.nanoTime();
                try {
                    if (recoverableCall.get()) {
                        recoverable.add(operation.userId(), operation.workoutId(), operation.exerciseId(), SET,
                                operation.operationId());
                    } else {
                        workouts.addSet(operation.userId(), operation.workoutId(), operation.exerciseId(), SET);
                    }
                    return new CallResult(Duration.ofNanos(System.nanoTime() - begin).toNanos() / 1_000_000.0, null);
                } catch (Throwable error) {
                    return new CallResult(Duration.ofNanos(System.nanoTime() - begin).toNanos() / 1_000_000.0,
                            error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
                }
            }));
        }
        if (!ready.await(30, TimeUnit.SECONDS)) throw new IllegalStateException(name + " workers not ready");
        Sampler sampler = new Sampler(jdbc, hikari());
        if (sample) sampler.start();
        long wallStart = System.nanoTime();
        start.countDown();
        List<Double> latencies = new ArrayList<>(operations.size());
        List<String> errors = new ArrayList<>();
        int errorCount = 0;
        for (Future<CallResult> future : futures) {
            CallResult call = future.get(120, TimeUnit.SECONDS);
            latencies.add(call.latencyMs());
            if (call.error() != null) {
                errorCount++;
                if (errors.size() < 5) errors.add(call.error());
            }
        }
        long durationNanos = System.nanoTime() - wallStart;
        sampler.close();
        executor.shutdownNow();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        latencies.sort(Comparator.naturalOrder());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("requests", operations.size());
        result.put("workers", workers);
        result.put("durationMs", round(durationNanos / 1_000_000.0));
        result.put("throughputRps", round(operations.size() / (durationNanos / 1_000_000_000.0)));
        result.put("p50Ms", percentile(latencies, .50));
        result.put("p95Ms", percentile(latencies, .95));
        result.put("p99Ms", percentile(latencies, .99));
        result.put("maxMs", latencies.isEmpty() ? 0 : round(latencies.getLast()));
        result.put("errors", errorCount);
        result.put("errorSamples", errors);
        result.putAll(sampler.snapshot());
        return result;
    }

    private List<Operation> operations(List<Seed> seeds, int count, boolean withReceipt) {
        List<Operation> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Seed seed = seeds.get(i % seeds.size());
            result.add(new Operation(seed.userId(), seed.workoutId(), seed.exerciseId(),
                    withReceipt ? UUID.randomUUID() : null));
        }
        return result;
    }

    private List<Seed> seedWorkouts(String prefix, int count) {
        List<Seed> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(seedWorkout(prefix + "-" + i));
        return result;
    }

    private Seed seedWorkout(String prefix) {
        String username = (prefix + "-" + UUID.randomUUID()).substring(0, Math.min(49, prefix.length() + 37));
        long userId = jdbc.queryForObject(
                "INSERT INTO users(username,password_hash) VALUES (?, 'load-test') RETURNING id", Long.class, username);
        long workoutId = jdbc.queryForObject("""
                INSERT INTO workout(user_id,name,status,started_at)
                VALUES (?, ?, 'IN_PROGRESS', CURRENT_TIMESTAMP) RETURNING id
                """, Long.class, userId, prefix);
        long sourceExerciseId = jdbc.queryForObject("SELECT id FROM exercise ORDER BY id LIMIT 1", Long.class);
        long workoutExerciseId = jdbc.queryForObject("""
                INSERT INTO workout_exercise(workout_id,exercise_id,sequence,exercise_name)
                SELECT ?, id, 1, name FROM exercise WHERE id=? RETURNING id
                """, Long.class, workoutId, sourceExerciseId);
        return new Seed(userId, workoutId, workoutExerciseId);
    }

    private Map<String, Object> counts(List<Seed> seeds) {
        String placeholders = String.join(",", java.util.Collections.nCopies(seeds.size(), "?"));
        Object[] ids = seeds.stream().map(Seed::exerciseId).toArray();
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM workout_set WHERE workout_exercise_id IN (" + placeholders + ")",
                Integer.class, ids);
        Integer distinctNumbers = jdbc.queryForObject("SELECT count(DISTINCT (workout_exercise_id, set_number)) "
                + "FROM workout_set WHERE workout_exercise_id IN (" + placeholders + ")", Integer.class, ids);
        Integer receipts = jdbc.queryForObject("SELECT count(*) FROM workout_set_receipt WHERE workout_id IN ("
                + String.join(",", java.util.Collections.nCopies(seeds.size(), "?")) + ")", Integer.class,
                seeds.stream().map(Seed::workoutId).toArray());
        return Map.of("actualRows", rows, "distinctSetNumbers", distinctNumbers, "receiptRows", receipts);
    }

    private HikariDataSource hikari() {
        return (HikariDataSource) dataSource;
    }

    private int setting(String name, int defaultValue) {
        return Integer.parseInt(System.getProperty(name, String.valueOf(defaultValue)));
    }

    private double percentile(List<Double> values, double percentile) {
        if (values.isEmpty()) return 0;
        int index = Math.min(values.size() - 1, (int) Math.ceil(values.size() * percentile) - 1);
        return round(values.get(index));
    }

    private double round(double value) { return Math.round(value * 100.0) / 100.0; }

    private record Seed(long userId, long workoutId, long exerciseId) {}
    private record Operation(long userId, long workoutId, long exerciseId, UUID operationId) {}
    private record CallResult(double latencyMs, String error) {}

    private static final class Sampler implements AutoCloseable {
        private final JdbcTemplate jdbc;
        private final HikariDataSource hikari;
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicLong maxActive = new AtomicLong();
        private final AtomicLong maxPoolPending = new AtomicLong();
        private final AtomicLong maxLockWaiters = new AtomicLong();
        private final AtomicLong maxHeapUsed = new AtomicLong();
        private Thread thread;

        private Sampler(JdbcTemplate jdbc, HikariDataSource hikari) {
            this.jdbc = jdbc;
            this.hikari = hikari;
        }

        void start() {
            running.set(true);
            thread = new Thread(this::sample, "write-load-metrics");
            thread.setDaemon(true);
            thread.start();
        }

        private void sample() {
            while (running.get()) {
                try {
                    Map<String, Object> row = jdbc.queryForMap("""
                            SELECT count(*) FILTER (WHERE state='active') AS active,
                                   count(*) FILTER (WHERE wait_event_type='Lock') AS lock_waiters
                            FROM pg_stat_activity WHERE datname=current_database()
                            """);
                    maxActive.accumulateAndGet(((Number) row.get("active")).longValue(), Math::max);
                    maxLockWaiters.accumulateAndGet(((Number) row.get("lock_waiters")).longValue(), Math::max);
                    var pool = hikari.getHikariPoolMXBean();
                    if (pool != null) maxPoolPending.accumulateAndGet(pool.getThreadsAwaitingConnection(), Math::max);
                    long heap = java.lang.management.ManagementFactory.getMemoryMXBean()
                            .getHeapMemoryUsage().getUsed();
                    maxHeapUsed.accumulateAndGet(heap, Math::max);
                } catch (RuntimeException ignored) {
                    // A saturated pool may reject a sampling connection; the workload result remains valid.
                }
                try { Thread.sleep(50); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
            }
        }

        Map<String, Object> snapshot() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dbActiveConnectionsMax", maxActive.get());
            result.put("dbLockWaitersMax", maxLockWaiters.get());
            result.put("hikariThreadsAwaitingConnectionMax", maxPoolPending.get());
            result.put("heapUsedBytesMax", maxHeapUsed.get());
            return result;
        }

        @Override public void close() throws InterruptedException {
            running.set(false);
            if (thread != null) {
                thread.interrupt();
                thread.join(2_000);
            }
        }
    }
}
