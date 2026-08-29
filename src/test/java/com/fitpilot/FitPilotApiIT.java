package com.fitpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.plan.application.TrainingPlanService;
import com.fitpilot.plan.dto.TrainingPlanDtos;
import com.fitpilot.infrastructure.performance.DistributedLockService;
import com.fitpilot.infrastructure.performance.RedisTokenBucketRateLimiter;
import com.fitpilot.infrastructure.performance.TwoLevelCache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FitPilotApiIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

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

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TrainingPlanService trainingPlanService;
    @Autowired JdbcTemplate jdbc;
    @Autowired TwoLevelCache cache;
    @Autowired RedisTokenBucketRateLimiter rateLimiter;
    @Autowired DistributedLockService locks;

    @Test
    void exposesPrometheusMetricsWithoutSensitiveRequestContent() throws Exception {
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("fitpilot_outbox_pending")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("password123"))));
    }

    @Test
    void completeUserPlanWorkoutPrAndAnalyticsFlow() throws Exception {
        register("flow_user");
        mvc.perform(post("/api/v1/auth/register").contentType("application/json")
                        .content(json.writeValueAsBytes(Map.of("username", "flow_user", "email", "other@example.com", "password", "password123"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(10002));
        String token = login("flow_user");

        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("flow_user"));
        mvc.perform(get("/api/v1/exercises").param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(50));

        JsonNode plan = call(post("/api/v1/training-plans").header("Authorization", bearer(token))
                .contentType("application/json").content("""
                    {"name":"Upper A","goal":"STRENGTH","durationWeeks":8,"days":[
                      {"dayNumber":1,"name":"Upper A","exercises":[
                        {"exerciseId":1,"sequence":1,"targetSets":4,"targetRepsMin":5,"targetRepsMax":8,"targetRpe":8,"restSeconds":180}
                      ]}
                    ]}
                    """), 201);
        long planId = plan.path("data").path("id").asLong();
        long dayId = plan.path("data").path("days").get(0).path("id").asLong();
        call(post("/api/v1/training-plans/{id}/activate", planId).header("Authorization", bearer(token)), 200);

        byte[] workoutBody = json.writeValueAsBytes(Map.of("trainingPlanId", planId, "trainingPlanDayId", dayId));
        String idempotencyKey = UUID.randomUUID().toString();
        var firstWorkout = mvc.perform(post("/api/v1/workouts").header("Authorization", bearer(token))
                        .header("Idempotency-Key", idempotencyKey).contentType("application/json").content(workoutBody))
                .andExpect(status().isCreated()).andReturn();
        var replayedWorkout = mvc.perform(post("/api/v1/workouts").header("Authorization", bearer(token))
                        .header("Idempotency-Key", idempotencyKey).contentType("application/json").content(workoutBody))
                .andExpect(status().isCreated()).andExpect(header().string("Idempotency-Replayed", "true")).andReturn();
        assertThat(replayedWorkout.getResponse().getContentAsByteArray())
                .isEqualTo(firstWorkout.getResponse().getContentAsByteArray());
        JsonNode workout = json.readTree(firstWorkout.getResponse().getContentAsByteArray());
        long workoutId = workout.path("data").path("id").asLong();
        long workoutExerciseId = workout.path("data").path("exercises").get(0).path("id").asLong();
        call(post("/api/v1/workouts/{workoutId}/exercises/{exerciseId}/sets", workoutId, workoutExerciseId)
                .header("Authorization", bearer(token)).contentType("application/json")
                .content("{\"weightKg\":80,\"reps\":5,\"rpe\":8,\"isWarmup\":false}"), 201);

        JsonNode completed = call(post("/api/v1/workouts/{id}/complete", workoutId)
                .header("Authorization", bearer(token)), 200);
        assertThat(completed.path("data").path("workout").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(completed.path("data").path("newPersonalRecords").asInt()).isZero();
        JsonNode repeated = call(post("/api/v1/workouts/{id}/complete", workoutId)
                .header("Authorization", bearer(token)), 200);
        assertThat(repeated.path("data").path("newPersonalRecords").asInt()).isZero();

        mvc.perform(get("/api/v1/personal-records").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/v1/analytics/overview").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.trainingVolume").value(0));
        mvc.perform(get("/api/v1/leaderboards/exercises/1").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='WorkoutCompletedEvent' AND status='PENDING'",
                Long.class)).isEqualTo(1L);

        mvc.perform(get("/api/v1/exercises/1")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/exercises/1")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/performance/cache-stats").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.hits").isNumber());

        register("other_user");
        String otherToken = login("other_user");
        mvc.perform(get("/api/v1/workouts/{id}", workoutId).header("Authorization", bearer(otherToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void performancePrimitivesAreAtomicAndBlockPenetration() throws Exception {
        String scope = UUID.randomUUID().toString();
        assertThat(rateLimiter.consume(scope, 2, 1).allowed()).isTrue();
        assertThat(rateLimiter.consume(scope, 2, 1).allowed()).isTrue();
        assertThat(rateLimiter.consume(scope, 2, 1).allowed()).isFalse();

        String lockKey = "fitpilot:test:lock:" + scope;
        var first = locks.tryLock(lockKey, Duration.ofSeconds(5));
        assertThat(first).isPresent();
        assertThat(locks.tryLock(lockKey, Duration.ofSeconds(5))).isEmpty();
        first.orElseThrow().close();
        var reacquired = locks.tryLock(lockKey, Duration.ofSeconds(5));
        assertThat(reacquired).isPresent();
        reacquired.orElseThrow().close();

        AtomicInteger loads = new AtomicInteger();
        String cacheId = UUID.randomUUID().toString();
        assertThat(cache.get("missing-test", cacheId, String.class,
                () -> { loads.incrementAndGet(); return Optional.empty(); })).isEmpty();
        assertThat(cache.get("missing-test", cacheId, String.class,
                () -> { loads.incrementAndGet(); return Optional.empty(); })).isEmpty();
        assertThat(loads).hasValue(1);

        AtomicInteger concurrentLoads = new AtomicInteger();
        String hotId = UUID.randomUUID().toString();
        var executor = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<String>>> futures = java.util.stream.IntStream.range(0, 24)
                .mapToObj(index -> executor.submit(() -> {
                    start.await();
                    return cache.get("single-flight-test", hotId, String.class, () -> {
                        concurrentLoads.incrementAndGet();
                        LockSupport.parkNanos(Duration.ofMillis(30).toNanos());
                        return Optional.of("hot-value");
                    });
                })).toList();
        start.countDown();
        for (Future<Optional<String>> future : futures) {
            assertThat(future.get(2, TimeUnit.SECONDS)).contains("hot-value");
        }
        executor.shutdownNow();
        assertThat(concurrentLoads).hasValue(1);
    }

    @Test
    void rollsBackWholePlanWhenExerciseInsertFails() throws Exception {
        JsonNode user = register("rollback_user");
        long userId = user.path("data").path("id").asLong();
        String overlongNotes = "x".repeat(300);
        var exercise = new TrainingPlanDtos.ExerciseRequest(1L, 1, 3, 5, 8,
                new BigDecimal("8"), 120, overlongNotes);
        var request = new TrainingPlanDtos.CreateRequest("rollback", null, "STRENGTH", 8,
                List.of(new TrainingPlanDtos.DayRequest(1, "day", null, List.of(exercise))));

        assertThatThrownBy(() -> trainingPlanService.create(userId, request))
                .isInstanceOf(DataIntegrityViolationException.class);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM training_plan WHERE user_id=?", Long.class, userId);
        assertThat(count).isZero();
    }

    @Test
    void loginRateLimitReturns429AfterTokenBucketIsExhausted() throws Exception {
        var requestBody = json.writeValueAsBytes(Map.of("username", "missing-user", "password", "password123"));
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/v1/auth/login").with(request -> {
                        request.setRemoteAddr("198.51.100.42");
                        return request;
                    }).contentType("application/json").content(requestBody))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/login").with(request -> {
                    request.setRemoteAddr("198.51.100.42");
                    return request;
                }).contentType("application/json").content(requestBody))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(50002));
    }

    @Test
    void agentRequiresOwnerConfirmationAndAuditsToolCalls() throws Exception {
        JsonNode owner=register("agent_owner"); String ownerToken=login("agent_owner");
        register("agent_other"); String otherToken=login("agent_other");
        JsonNode session=call(post("/api/v1/agent/sessions").header("Authorization",bearer(ownerToken)),201);
        String sessionId=session.path("data").path("id").asText();
        JsonNode proposal=call(post("/api/v1/agent/sessions/"+sessionId+"/messages")
                .header("Authorization",bearer(ownerToken)).contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("message","帮我制定新计划"))),200);
        assertThat(proposal.path("data").path("confirmationRequired").asBoolean()).isTrue();
        String actionId=proposal.path("data").path("pendingAction").path("id").asText();
        String confirmationToken=proposal.path("data").path("pendingAction").path("confirmationToken").asText();
        long ownerId=owner.path("data").path("id").asLong();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM training_plan WHERE user_id=?",Long.class,ownerId)).isZero();

        mvc.perform(post("/api/v1/agent/pending-actions/"+actionId+"/confirm").header("Authorization",bearer(otherToken))
                .contentType("application/json").content(json.writeValueAsBytes(Map.of("confirmationToken",confirmationToken))))
                .andExpect(status().isNotFound());
        call(post("/api/v1/agent/pending-actions/"+actionId+"/confirm").header("Authorization",bearer(ownerToken))
                .contentType("application/json").content(json.writeValueAsBytes(Map.of("confirmationToken",confirmationToken))),200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM training_plan WHERE user_id=? AND status='DRAFT'",Long.class,ownerId)).isEqualTo(1);
        mvc.perform(post("/api/v1/agent/pending-actions/"+actionId+"/confirm").header("Authorization",bearer(ownerToken))
                .contentType("application/json").content(json.writeValueAsBytes(Map.of("confirmationToken",confirmationToken))))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_execution WHERE user_id=?",Long.class,ownerId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_tool_call",Long.class)).isGreaterThanOrEqualTo(7);

        mvc.perform(post("/mcp").header("Authorization",bearer(ownerToken))
                .header("MCP-Protocol-Version","2026-07-28").header("Mcp-Method","tools/call").header("Mcp-Name","get_user_profile")
                .contentType("application/json").content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.content[0].type").value("text"));
    }

    @Test
    void runsVersionedAgentEvaluationDataset() throws Exception {
        JsonNode started=call(post("/api/v1/operations/evaluations/agent/runs")
                .header("X-Operations-Token","test-operations-token").contentType("application/json")
                .content("{\"mode\":\"RULE_WORKFLOW\"}"),202);
        String runId=started.path("data").path("runId").asText();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(()->{
            JsonNode run=call(get("/api/v1/operations/evaluations/runs/"+runId)
                    .header("X-Operations-Token","test-operations-token"),200).path("data");
            assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(run.path("totalCases").asInt()).isGreaterThanOrEqualTo(150);
            assertThat(run.path("metrics").path("toolSelectionAccuracy").asDouble()).isGreaterThanOrEqualTo(0.95);
            assertThat(run.path("metrics").path("constraintViolationRate").asDouble()).isZero();
        });
    }

    private JsonNode register(String username) throws Exception {
        return call(post("/api/v1/auth/register").contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("username", username, "email", username + "@example.com",
                        "password", "password123"))), 201);
    }

    private String login(String username) throws Exception {
        JsonNode response = call(post("/api/v1/auth/login").contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("username", username, "password", "password123"))), 200);
        return response.path("data").path("accessToken").asText();
    }

    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, int expectedStatus)
            throws Exception {
        String body = mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private String bearer(String token) { return "Bearer " + token; }
}
