package com.fitpilot.agent.adjustment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.infrastructure.events.EventTypes;
import com.fitpilot.infrastructure.events.WorkoutCompletedEventHandler;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class TrainingAdjustmentEvidenceIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    private static JdbcTemplate jdbc;
    private static TrainingAdjustmentRepository repository;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new TrainingAdjustmentRepository(jdbc, new ObjectMapper());
    }

    @Test
    void readsCommittedVolumeAndRequiresThePrReceiptForEachRecentWorkout() {
        long owner = user("training-evidence");
        long previous = workout(owner, 20, "COMPLETED");
        long recent = workout(owner, 2, "COMPLETED");
        workout(owner, 29, "COMPLETED");
        workout(owner, 3, "CANCELLED");
        workout(user("unrelated-evidence"), 2, "COMPLETED");
        long previousExercise = exercise(previous, 3);
        long recentExercise = exercise(recent, 4);
        set(previousExercise, 1, 50, 10, 7, false, true);
        set(recentExercise, 1, 100, 5, 8, false, true);
        set(recentExercise, 2, 50, 10, 9, true, true);
        set(recentExercise, 3, 150, 5, 9, false, false);
        jdbc.update("""
                INSERT INTO workout_feedback(workout_id,user_id,fatigue_score,pain_score,updated_at)
                VALUES (?,?,4,6,CURRENT_TIMESTAMP - INTERVAL '20 days'), (?,?,6,2,CURRENT_TIMESTAMP)
                """, previous, owner, recent, owner);

        UUID previousEvent = completionEvent(previous);
        receipt(previousEvent, WorkoutCompletedEventHandler.PR_CONSUMER);
        UUID recentEvent = completionEvent(recent);
        receipt(recentEvent, "fitpilot-analytics-projector-v1");

        assertThat(repository.metrics(owner)).isEqualTo(new TrainingAdjustmentRepository.Metrics(
                2, 2, 7, 7.5, 2, 5, 2, 500, 500, 0, false));

        receipt(recentEvent, WorkoutCompletedEventHandler.PR_CONSUMER);

        assertThat(repository.metrics(owner)).isEqualTo(new TrainingAdjustmentRepository.Metrics(
                2, 2, 7, 7.5, 2, 5, 2, 500, 500, 0, true));

        workout(owner, 1, "COMPLETED");
        assertThat(repository.metrics(owner).prProjectionComplete()).isFalse();
    }

    private static long user(String username) {
        return jdbc.queryForObject("""
                INSERT INTO users(username,email,password_hash) VALUES (?,?,'fixture') RETURNING id
                """, Long.class, username, username + "@example.test");
    }

    private static long workout(long userId, int daysAgo, String status) {
        return jdbc.queryForObject("""
                INSERT INTO workout(user_id,status,started_at,completed_at,duration_seconds)
                VALUES (?,?,CURRENT_TIMESTAMP-make_interval(days => ?) - INTERVAL '1 hour',
                        CURRENT_TIMESTAMP-make_interval(days => ?),3600) RETURNING id
                """, Long.class, userId, status, daysAgo, daysAgo);
    }

    private static long exercise(long workoutId, int targets) {
        return jdbc.queryForObject("""
                INSERT INTO workout_exercise(workout_id,exercise_id,sequence,exercise_name,target_sets)
                VALUES (?,1,1,'fixture',?) RETURNING id
                """, Long.class, workoutId, targets);
    }

    private static void set(long exerciseId, int number, int weight, int reps, int rpe, boolean warmup, boolean complete) {
        jdbc.update("""
                INSERT INTO workout_set(workout_exercise_id,set_number,weight_kg,reps,rpe,is_warmup,completed_at)
                VALUES (?,?,?,?,?,?, CASE WHEN ? THEN CURRENT_TIMESTAMP END)
                """, exerciseId, number, weight, reps, rpe, warmup, complete);
    }

    private static UUID completionEvent(long workoutId) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,topic,event_key,payload,status)
                VALUES (?,'Workout',?,?,?,?,'{}','SENT')
                """, eventId, Long.toString(workoutId), EventTypes.WORKOUT_COMPLETED,
                EventTypes.topicFor(EventTypes.WORKOUT_COMPLETED), Long.toString(workoutId));
        return eventId;
    }

    private static void receipt(UUID eventId, String consumer) {
        jdbc.update("INSERT INTO processed_event(event_id,consumer) VALUES (?,?)", eventId, consumer);
    }
}
