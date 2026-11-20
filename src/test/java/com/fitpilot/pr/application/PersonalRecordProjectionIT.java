package com.fitpilot.pr.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.infrastructure.events.EventEnvelope;
import com.fitpilot.infrastructure.events.EventPayloads;
import com.fitpilot.infrastructure.events.EventTypes;
import com.fitpilot.infrastructure.events.WorkoutCompletedEventHandler;
import com.fitpilot.pr.dto.PersonalRecordView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {"fitpilot.events.enabled=false", "fitpilot.rag.enabled=false",
        "security.jwt.secret=integration-test-secret-key-with-32-bytes-minimum"})
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PersonalRecordProjectionIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    private static final LocalDateTime FIRST_WORKOUT = LocalDateTime.of(2026, 1, 1, 12, 0);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired WorkoutCompletedEventHandler handler;
    @Autowired PersonalRecordService records;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean LeaderboardService leaderboard;
    private long userId;

    @BeforeEach
    void seedUser() {
        userId = jdbc.queryForObject("INSERT INTO users(username,password_hash) VALUES (?, 'test') RETURNING id",
                Long.class, UUID.randomUUID().toString());
    }

    @Test
    void lateHigherScoreWinsAndLowerOrRepeatedEventsDoNotCreateRecords() throws Exception {
        long highest = seedWorkout(100, FIRST_WORKOUT);
        long later = seedWorkout(90, FIRST_WORKOUT.plusDays(1));
        long lowerThanBest = seedWorkout(95, FIRST_WORKOUT.plusDays(2));
        String highestEvent = event(highest, FIRST_WORKOUT);

        handler.projectPersonalRecords(event(later, FIRST_WORKOUT.plusDays(1)));
        handler.projectPersonalRecords(highestEvent);
        handler.projectPersonalRecords(event(lowerThanBest, FIRST_WORKOUT.plusDays(2)));
        handler.projectPersonalRecords(highestEvent);
        handler.projectPersonalRecords(event(highest, FIRST_WORKOUT));

        assertBestFrom(highest);
        assertThat(records.history(userId, 1L)).hasSize(8);
        assertThat(records.countByWorkout(userId, lowerThanBest)).isZero();
        assertThat(recordEventCount()).isEqualTo(8);
    }

    @Test
    void historicalRowsKeepTheirBestByRecordTypeAfterSourceRemoval() {
        jdbc.update("""
                INSERT INTO personal_record(user_id,exercise_id,record_type,weight_kg,reps,estimated_1rm,achieved_at)
                VALUES (?,1,'MAX_WEIGHT',100,3,110,?), (?,1,'MAX_WEIGHT',90,8,114,?),
                       (?,1,'MAX_VOLUME',100,3,110,?), (?,1,'MAX_VOLUME',90,8,114,?),
                       (?,1,'ESTIMATED_1RM',100,3,110,?), (?,1,'ESTIMATED_1RM',90,8,114,?)
                """, userId, FIRST_WORKOUT, userId, FIRST_WORKOUT.plusDays(1),
                userId, FIRST_WORKOUT.plusDays(1), userId, FIRST_WORKOUT,
                userId, FIRST_WORKOUT.plusDays(1), userId, FIRST_WORKOUT);

        assertThat(records.current(userId)).extracting(PersonalRecordView::recordType, PersonalRecordView::reps)
                .containsExactlyInAnyOrder(tuple("MAX_WEIGHT", 3), tuple("MAX_VOLUME", 8),
                        tuple("ESTIMATED_1RM", 8));
        assertThat(records.current(userId)).allSatisfy(value -> assertThat(value.workoutSetId()).isNull());
        assertThat(records.history(userId, 1L)).hasSize(6);
    }

    @Test
    void competingWorkoutWaitsAndComparesAgainstTheCommittedBest() throws Exception {
        long highest = seedWorkout(100, FIRST_WORKOUT);
        long lower = seedWorkout(90, FIRST_WORKOUT.plusDays(1));
        String highestEvent = event(highest, FIRST_WORKOUT);
        String lowerEvent = event(lower, FIRST_WORKOUT.plusDays(1));
        var followerPid = new CompletableFuture<Integer>();
        var tx = new TransactionTemplate(transactions);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var follower = tx.execute(status -> {
                handler.projectPersonalRecords(highestEvent);
                int leaderPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                var future = executor.submit(() -> tx.executeWithoutResult(ignored -> {
                    jdbc.execute("SET LOCAL lock_timeout = '10s'");
                    followerPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    handler.projectPersonalRecords(lowerEvent);
                }));
                await().atMost(Duration.ofSeconds(5)).until(() -> followerPid.isDone()
                        && Boolean.TRUE.equals(jdbc.queryForObject(
                                "SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class,
                                leaderPid, followerPid.join())));
                return future;
            });
            follower.get(12, TimeUnit.SECONDS);
        }

        assertBestFrom(highest);
        assertThat(records.history(userId, 1L)).hasSize(4);
        assertThat(records.countByWorkout(userId, lower)).isZero();
        assertThat(recordEventCount()).isEqualTo(4);
    }

    private void assertBestFrom(long workoutId) {
        assertThat(records.current(userId)).hasSize(4).extracting(PersonalRecordView::workoutId)
                .containsOnly(workoutId);
    }

    private long seedWorkout(int weight, LocalDateTime completedAt) {
        return jdbc.queryForObject("""
                WITH new_workout AS (
                    INSERT INTO workout(user_id,status,started_at,completed_at)
                    VALUES (?, 'COMPLETED', ?, ?) RETURNING id
                ), new_exercise AS (
                    INSERT INTO workout_exercise(workout_id,exercise_id,sequence,exercise_name)
                    SELECT id, 1, 1, 'Squat' FROM new_workout RETURNING id
                ), new_set AS (
                    INSERT INTO workout_set(workout_exercise_id,set_number,weight_kg,reps,completed_at)
                    SELECT id, 1, ?, 5, ? FROM new_exercise
                )
                SELECT id FROM new_workout
                """, Long.class, userId, completedAt.minusMinutes(1), completedAt, weight, completedAt);
    }

    private String event(long workoutId, LocalDateTime completedAt) throws JsonProcessingException {
        var payload = new EventPayloads.WorkoutCompleted(workoutId, userId, completedAt, 60, 1);
        return json.writeValueAsString(new EventEnvelope(UUID.randomUUID(), EventTypes.WORKOUT_COMPLETED,
                1, "Workout", String.valueOf(workoutId), Instant.now(), null, json.valueToTree(payload)));
    }

    private int recordEventCount() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox_event WHERE event_type=? AND payload->'payload'->>'userId'=?
                """, Integer.class, EventTypes.PERSONAL_RECORD_CREATED, String.valueOf(userId));
    }
}
