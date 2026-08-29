package com.fitpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.infrastructure.events.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FitPilotEventFlowIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("security.jwt.secret", () -> "integration-test-secret-key-with-32-bytes-minimum");
        registry.add("fitpilot.performance.rate-limit.enabled", () -> "false");
        registry.add("fitpilot.events.relay.fixed-delay-ms", () -> "100");
        registry.add("fitpilot.events.consumer.retry-interval-ms", () -> "50");
        registry.add("fitpilot.events.consumer.max-attempts", () -> "2");
        registry.add("fitpilot.operations.token", () -> "test-operations-token");
        registry.add("fitpilot.rag.enabled", () -> "false");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired WorkoutCompletedEventHandler workoutHandler;
    @Autowired PersonalRecordEventHandler recordHandler;
    @Autowired DeadLetterService deadLetters;

    @Test
    void relaysWorkoutAndProjectsPrAnalyticsAndNotificationsIdempotently() throws Exception {
        String token = registerAndLogin("event_user");
        JsonNode plan = call(post("/api/v1/training-plans").header("Authorization", bearer(token))
                .contentType("application/json").content("""
                    {"name":"Event Plan","goal":"STRENGTH","durationWeeks":8,"days":[
                      {"dayNumber":1,"name":"Heavy Day","exercises":[
                        {"exerciseId":1,"sequence":1,"targetSets":3,"targetRepsMin":5,"targetRepsMax":5}
                      ]}
                    ]}
                    """), 201);
        long planId = plan.path("data").path("id").asLong();
        long dayId = plan.path("data").path("days").get(0).path("id").asLong();
        call(post("/api/v1/training-plans/{id}/activate", planId).header("Authorization", bearer(token)), 200);
        JsonNode workout = call(post("/api/v1/workouts").header("Authorization", bearer(token))
                .contentType("application/json").content(json.writeValueAsBytes(
                        Map.of("trainingPlanId", planId, "trainingPlanDayId", dayId))), 201);
        long workoutId = workout.path("data").path("id").asLong();
        long workoutExerciseId = workout.path("data").path("exercises").get(0).path("id").asLong();
        call(post("/api/v1/workouts/{workoutId}/exercises/{exerciseId}/sets", workoutId, workoutExerciseId)
                .header("Authorization", bearer(token)).contentType("application/json")
                .content("{\"weightKg\":80,\"reps\":5,\"isWarmup\":false}"), 201);

        JsonNode completed = call(post("/api/v1/workouts/{id}/complete", workoutId)
                .header("Authorization", bearer(token)), 200);
        assertThat(completed.path("data").path("newPersonalRecords").asInt()).isZero();

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(count("SELECT COUNT(*) FROM personal_record WHERE workout_id=" + workoutId)).isEqualTo(4);
            assertThat(count("SELECT COUNT(*) FROM workout_analytics_projection WHERE workout_id=" + workoutId)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM user_notification")).isEqualTo(4);
            assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE status='PENDING'")).isZero();
        });

        JsonNode overview = call(get("/api/v1/analytics/overview").header("Authorization", bearer(token)), 200);
        assertThat(overview.path("data").path("trainingVolume").decimalValue()).isEqualByComparingTo("400.00");
        JsonNode notifications = call(get("/api/v1/notifications").header("Authorization", bearer(token)), 200);
        assertThat(notifications.path("data")).hasSize(4);

        String workoutEvent = jdbc.queryForObject("""
                SELECT payload::text FROM outbox_event
                WHERE aggregate_id=? AND event_type='WorkoutCompletedEvent'
                """, String.class, String.valueOf(workoutId));
        workoutHandler.projectPersonalRecords(workoutEvent);
        workoutHandler.projectAnalytics(workoutEvent);
        String recordEvent = jdbc.queryForObject("""
                SELECT payload::text FROM outbox_event WHERE event_type='PersonalRecordCreatedEvent' ORDER BY id LIMIT 1
                """, String.class);
        recordHandler.notifyUser(recordEvent);
        assertThat(count("SELECT COUNT(*) FROM personal_record WHERE workout_id=" + workoutId)).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM user_notification")).isEqualTo(4);
    }

    @Test
    void retriesPoisonEventThenPersistsAndPublishesDeadLetter() throws Exception {
        kafka.send(EventTopics.WORKOUT_COMPLETED, "poison", "{not-json").get();
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(count("SELECT COUNT(*) FROM dead_letter_event WHERE status='OPEN'")).isGreaterThanOrEqualTo(2));
        assertThat(deadLetters.list(10)).isNotEmpty();
    }

    private String registerAndLogin(String username) throws Exception {
        call(post("/api/v1/auth/register").contentType("application/json").content(json.writeValueAsBytes(
                Map.of("username", username, "email", username + "@example.com", "password", "password123"))), 201);
        return call(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsBytes(
                Map.of("username", username, "password", "password123"))), 200)
                .path("data").path("accessToken").asText();
    }

    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, int status)
            throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().is(status)).andReturn()
                .getResponse().getContentAsByteArray());
    }

    private long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
    private String bearer(String token) { return "Bearer " + token; }
}
