package com.fitpilot.workout.application;

import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.workout.dto.WorkoutDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Spring proxies and real PostgreSQL locks; pg_blocking_pids proves the intended interleaving. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkoutConcurrencyIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("security.jwt.secret", () -> "integration-test-secret-key-with-32-bytes-minimum");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("fitpilot.events.enabled", () -> "false");
        registry.add("fitpilot.rag.enabled", () -> "false");
        registry.add("fitpilot.operations.token", () -> "test-operations-token");
    }

    @Autowired WorkoutService workouts;
    @Autowired RecoverableSetService recoverable;
    @Autowired WorkoutFeedbackService feedback;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private long userId;
    private long workoutId;
    private long exerciseId;
    private long setId;
    private static final WorkoutDtos.SetRequest SET = new WorkoutDtos.SetRequest(
            new BigDecimal("50"), 8, null, null, false, false);

    @BeforeEach
    void seed() {
        userId = jdbc.queryForObject("INSERT INTO users(username,password_hash) VALUES (?, 'test') RETURNING id",
                Long.class, UUID.randomUUID().toString());
        workoutId = jdbc.queryForObject("""
                INSERT INTO workout(user_id,name,status,started_at)
                VALUES (?, 'concurrency', 'IN_PROGRESS', CURRENT_TIMESTAMP) RETURNING id
                """, Long.class, userId);
        exerciseId = workouts.addExercise(userId, workoutId, new WorkoutDtos.AddExerciseRequest(1L, null)).id();
        setId = workouts.addSet(userId, workoutId, exerciseId, SET).id();
    }

    @ParameterizedTest
    @CsvSource({"COMPLETED,exercise", "COMPLETED,add", "COMPLETED,update", "COMPLETED,delete",
            "CANCELLED,exercise", "CANCELLED,add", "CANCELLED,update", "CANCELLED,delete"})
    void queuedWriteRechecksTerminalState(String terminal, String operation) throws Exception {
        var before = workouts.get(userId, workoutId).exercises();
        Throwable failure = afterLocked(() -> finish(terminal), () -> catchThrowable(() -> mutate(operation)));
        assertThat(failure).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(error.errorCode()).isEqualTo("COMPLETED".equals(terminal)
                    ? ErrorCode.WORKOUT_ALREADY_COMPLETED : ErrorCode.WORKOUT_CANCELLED);
        });
        assertThat(workouts.get(userId, workoutId).status()).isEqualTo(terminal);
        assertThat(workouts.get(userId, workoutId).exercises()).isEqualTo(before);
    }

    @Test
    void concurrentExerciseAppendsBothSucceedWithDistinctSequences() throws Exception {
        var second = afterLocked(() -> workouts.addExercise(userId, workoutId,
                new WorkoutDtos.AddExerciseRequest(1L, "first")),
                () -> workouts.addExercise(userId, workoutId, new WorkoutDtos.AddExerciseRequest(1L, "second")));
        assertThat(second.sequence()).isEqualTo(3);
        assertThat(workouts.get(userId, workoutId).exercises()).extracting(WorkoutDtos.ExerciseView::sequence)
                .containsExactly(1, 2, 3);
    }

    @ParameterizedTest
    @CsvSource({"COMPLETED,COMPLETED", "CANCELLED,CANCELLED", "COMPLETED,CANCELLED", "CANCELLED,COMPLETED"})
    void terminalCommandsAreIdempotentOrConflict(String first, String second) throws Exception {
        var failure = afterLocked(() -> finish(first), () -> catchThrowable(() -> finish(second)));
        if (first.equals(second)) assertThat(failure).isNull();
        else assertThat(failure).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(workouts.get(userId, workoutId).status()).isEqualTo(first);
        assertThat(eventCount()).isEqualTo(first.equals("COMPLETED") ? 1 : 0);
    }

    @Test
    void rollbackReleasesLockAndRollsBackCompletionAndOutboxTogether() throws Exception {
        var result = afterLocked(() -> workouts.complete(userId, workoutId),
                () -> workouts.addExercise(userId, workoutId, new WorkoutDtos.AddExerciseRequest(1L, null)), true);
        assertThat(result.sequence()).isEqualTo(2);
        assertThat(workouts.get(userId, workoutId).status()).isEqualTo("IN_PROGRESS");
        assertThat(eventCount()).isZero();
    }

    @Test
    void concurrentOperationIdReplaysOneReceiptAndStillReplaysAfterCompletion() throws Exception {
        UUID operation = UUID.randomUUID();
        var replay = afterLocked(() -> recoverable.add(userId, workoutId, exerciseId, SET, operation),
                () -> recoverable.add(userId, workoutId, exerciseId, SET, operation));
        workouts.complete(userId, workoutId);
        assertThat(recoverable.add(userId, workoutId, exerciseId, SET, operation)).isEqualTo(replay);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workout_set WHERE workout_exercise_id=?",
                Integer.class, exerciseId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workout_set_receipt WHERE workout_id=?",
                Integer.class, workoutId)).isEqualTo(1);
        assertThatThrownBy(() -> recoverable.add(userId, workoutId, exerciseId, SET, UUID.randomUUID()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.WORKOUT_ALREADY_COMPLETED));
    }

    @Test
    void feedbackWaitsForCompletionAndCannotWriteForAnotherUser() throws Exception {
        var request = new WorkoutDtos.FeedbackRequest(5, 0, "fine");
        var result = afterLocked(() -> workouts.complete(userId, workoutId),
                () -> feedback.upsert(userId, workoutId, request));
        assertThat(result.workoutId()).isEqualTo(workoutId);
        assertThatThrownBy(() -> feedback.upsert(userId + 100000, workoutId, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void ownershipAndDatabaseUniquenessRemainEnforced() {
        assertThatThrownBy(() -> workouts.addExercise(userId + 100000, workoutId,
                new WorkoutDtos.AddExerciseRequest(1L, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO workout_exercise(workout_id,exercise_id,sequence,exercise_name) VALUES (?,1,1,'duplicate')
                """, workoutId)).isInstanceOf(DuplicateKeyException.class);
        assertThat(workouts.get(userId, workoutId).exercises()).hasSize(1);
    }

    private void mutate(String operation) {
        switch (operation) {
            case "exercise" -> workouts.addExercise(userId, workoutId, new WorkoutDtos.AddExerciseRequest(1L, null));
            case "add" -> recoverable.add(userId, workoutId, exerciseId, SET, UUID.randomUUID());
            case "update" -> workouts.updateSet(userId, workoutId, setId,
                    new WorkoutDtos.SetRequest(new BigDecimal("60"), 10, null, null, false, false));
            case "delete" -> workouts.deleteSet(userId, workoutId, setId);
            default -> throw new IllegalArgumentException(operation);
        }
    }

    private void finish(String status) {
        if (status.equals("COMPLETED")) workouts.complete(userId, workoutId);
        else workouts.cancel(userId, workoutId);
    }

    private int eventCount() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_type='Workout' AND aggregate_id=?",
                Integer.class, String.valueOf(workoutId));
    }

    private <T> T afterLocked(Runnable first, Supplier<T> second) throws Exception {
        return afterLocked(first, second, false);
    }

    private <T> T afterLocked(Runnable first, Supplier<T> second, boolean rollback) throws Exception {
        var pid = new CompletableFuture<Integer>();
        var follower = new AtomicReference<Future<T>>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                first.run();
                follower.set(executor.submit(() -> new TransactionTemplate(transactions).execute(ignored -> {
                    jdbc.execute("SET LOCAL lock_timeout = '8s'");
                    jdbc.execute("SET LOCAL statement_timeout = '10s'");
                    pid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    T result = second.get();
                    // Expected business rejection has already marked the joined service transaction rollback-only.
                    if (result instanceof Throwable) ignored.setRollbackOnly();
                    return result;
                })));
                await().atMost(Duration.ofSeconds(5)).until(() -> pid.isDone()
                        && Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean.class, pid.join())));
                if (rollback) status.setRollbackOnly();
            });
            return follower.get().get(12, TimeUnit.SECONDS);
        }
    }
}
