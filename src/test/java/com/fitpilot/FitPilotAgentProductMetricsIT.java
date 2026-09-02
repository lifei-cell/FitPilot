package com.fitpilot;

import com.fitpilot.agent.product.AgentProductMetricsPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FitPilotAgentProductMetricsIT {
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
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("security.jwt.secret", () -> "integration-test-secret-key-with-32-bytes-minimum");
        registry.add("fitpilot.events.enabled", () -> "false");
        registry.add("fitpilot.rag.enabled", () -> "false");
        registry.add("fitpilot.performance.rate-limit.enabled", () -> "false");
        registry.add("fitpilot.operations.token", () -> "product-metrics-token");
        registry.add("fitpilot.agent.product-metrics.window-days", () -> "90");
        registry.add("fitpilot.agent.product-metrics.initial-delay-ms", () -> "600000");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentProductMetricsPublisher publisher;

    @Test
    void exposesAuditableBusinessValueMetricsAndPrometheusGauges() throws Exception {
        seedBusinessValueCohort();

        mvc.perform(get("/api/v1/operations/agent/product-metrics")
                        .header("X-Operations-Token", "product-metrics-token")
                        .param("windowDays", "90")
                        .param("outcomeWindowDays", "28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionRetention.eligibleUsers").value(2))
                .andExpect(jsonPath("$.data.sessionRetention.retainedUsers").value(1))
                .andExpect(jsonPath("$.data.sessionRetention.rate").value(0.5))
                .andExpect(jsonPath("$.data.suggestionFunnel.generated").value(2))
                .andExpect(jsonPath("$.data.suggestionFunnel.acceptanceRate").value(0.5))
                .andExpect(jsonPath("$.data.suggestionFunnel.rejectionRate").value(0.5))
                .andExpect(jsonPath("$.data.suggestionFunnel.confirmationConversionRate").value(0.5))
                .andExpect(jsonPath("$.data.reliability.executions").value(4))
                .andExpect(jsonPath("$.data.reliability.ruleFallbackRate").value(0.25))
                .andExpect(jsonPath("$.data.reliability.costPerSuccessfulExecutionUsd").value(0.02))
                .andExpect(jsonPath("$.data.trainingOutcome.eligibleAdjustments").value(1))
                .andExpect(jsonPath("$.data.trainingOutcome.before.completionRate").value(0.5))
                .andExpect(jsonPath("$.data.trainingOutcome.after.completionRate").value(1.0))
                .andExpect(jsonPath("$.data.trainingOutcome.delta.completionRate").value(0.5))
                .andExpect(jsonPath("$.data.trainingOutcome.delta.averagePain").value(-3.0))
                .andExpect(jsonPath("$.data.trainingOutcome.delta.trainingVolume").value(500.0))
                .andExpect(jsonPath("$.data.trainingOutcome.delta.trainingVolumeRate").value(0.5))
                .andExpect(jsonPath("$.data.trainingOutcome.delta.personalRecords").value(1));

        mvc.perform(get("/api/v1/operations/agent/product-metrics")
                        .header("X-Operations-Token", "wrong"))
                .andExpect(status().isForbidden());

        publisher.refresh();
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "fitpilot_agent_product_session_retention_rate 0.5")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "fitpilot_agent_product_outcome_pain_delta -3.0")));
    }

    private void seedBusinessValueCohort() {
        long user = user("metrics_retained");
        long otherUser = user("metrics_not_retained");
        UUID session = session(user, "CURRENT_TIMESTAMP-INTERVAL '10 days'");
        UUID otherSession = session(otherUser, "CURRENT_TIMESTAMP-INTERVAL '10 days'");
        message(session, "CURRENT_TIMESTAMP-INTERVAL '10 days'");
        message(session, "CURRENT_TIMESTAMP-INTERVAL '5 days'");
        message(otherSession, "CURRENT_TIMESTAMP-INTERVAL '10 days'");

        long sourcePlan = plan(user, "ARCHIVED", null);
        long activePlan = plan(user, "ACTIVE", "CURRENT_DATE-35");
        acceptedAdjustment(user, sourcePlan, activePlan);
        rejectedAdjustment(user, sourcePlan);
        executions(user, session);

        long beforeCompleted = workout(user, activePlan, "COMPLETED", "CURRENT_TIMESTAMP-INTERVAL '50 days'");
        long beforeCancelled = workout(user, activePlan, "CANCELLED", "CURRENT_TIMESTAMP-INTERVAL '45 days'");
        long afterCompletedOne = workout(user, activePlan, "COMPLETED", "CURRENT_TIMESTAMP-INTERVAL '30 days'");
        long afterCompletedTwo = workout(user, activePlan, "COMPLETED", "CURRENT_TIMESTAMP-INTERVAL '20 days'");
        feedback(user, beforeCompleted, 4);
        feedback(user, beforeCancelled, 4);
        feedback(user, afterCompletedOne, 1);
        feedback(user, afterCompletedTwo, 1);
        analytics(user, beforeCompleted, "CURRENT_TIMESTAMP-INTERVAL '50 days'", "1000");
        analytics(user, afterCompletedOne, "CURRENT_TIMESTAMP-INTERVAL '30 days'", "700");
        analytics(user, afterCompletedTwo, "CURRENT_TIMESTAMP-INTERVAL '20 days'", "800");
        jdbc.update("""
                INSERT INTO personal_record(user_id,exercise_id,record_type,weight_kg,reps,estimated_1rm,
                  workout_id,achieved_at) VALUES (?,1,'WEIGHT',100,5,116.67,?,CURRENT_TIMESTAMP-INTERVAL '20 days')
                """, user, afterCompletedTwo);
    }

    private long user(String username) {
        return jdbc.queryForObject("INSERT INTO users(username,email,password_hash) VALUES (?,?,?) RETURNING id",
                Long.class, username, username + "@example.com", "not-used");
    }

    private UUID session(long userId, String createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO agent_session(id,user_id,status,created_at,updated_at) VALUES (?,?,'ACTIVE',"
                + createdAt + "," + createdAt + ")", id, userId);
        return id;
    }

    private void message(UUID sessionId, String createdAt) {
        jdbc.update("INSERT INTO agent_message(session_id,role,content,created_at) VALUES (?,'user','hello',"
                + createdAt + ")", sessionId);
    }

    private long plan(long userId, String status, String startedAt) {
        String started = startedAt == null ? "NULL" : startedAt;
        return jdbc.queryForObject("""
                INSERT INTO training_plan(user_id,name,goal,duration_weeks,days_per_week,status,started_at)
                VALUES (?,'Metric plan','STRENGTH',8,3,?,%s) RETURNING id
                """.formatted(started), Long.class, userId, status);
    }

    private void acceptedAdjustment(long userId, long sourcePlan, long activePlan) {
        jdbc.update("""
                INSERT INTO plan_adjustment(id,user_id,source_plan_id,source_plan_version,evidence,reasons,proposal,
                  rule,status,draft_plan_id,model,prompt_version,degraded,created_at,decided_at)
                VALUES (?,?,?,1,'{}','[]','{}','PROGRESSIVE_OVERLOAD','ACCEPTED',?,'gpt-test','v1',false,
                  CURRENT_TIMESTAMP-INTERVAL '36 days',CURRENT_TIMESTAMP-INTERVAL '36 days')
                """, UUID.randomUUID(), userId, sourcePlan, activePlan);
    }

    private void rejectedAdjustment(long userId, long sourcePlan) {
        jdbc.update("""
                INSERT INTO plan_adjustment(id,user_id,source_plan_id,source_plan_version,evidence,reasons,proposal,
                  rule,status,model,prompt_version,degraded,created_at,decided_at)
                VALUES (?,?,?,1,'{}','[]','{}','RECOVERY','REJECTED','RULE_WORKFLOW','v1',true,
                  CURRENT_TIMESTAMP-INTERVAL '12 days',CURRENT_TIMESTAMP-INTERVAL '12 days')
                """, UUID.randomUUID(), userId, sourcePlan);
    }

    private void executions(long userId, UUID sessionId) {
        execution(userId, sessionId, "SUCCEEDED", "gpt-test", false, "0.02000000");
        execution(userId, sessionId, "SUCCEEDED", "gpt-test", false, "0.01000000");
        execution(userId, sessionId, "AWAITING_CONFIRMATION", "gpt-test", false, "0.03000000");
        execution(userId, sessionId, "FAILED", "RULE_WORKFLOW", true, "0.00000000");
    }

    private void execution(long userId, UUID sessionId, String status, String model,
                           boolean degraded, String cost) {
        jdbc.update("""
                INSERT INTO agent_execution(id,user_id,session_id,intent,status,model,prompt_version,degraded,cost_usd,
                  created_at) VALUES (?,?,?,'PLAN_ADJUSTMENT',?,?, 'v1',?,?,CURRENT_TIMESTAMP-INTERVAL '2 days')
                """, UUID.randomUUID(), userId, sessionId, status, model, degraded, new java.math.BigDecimal(cost));
    }

    private long workout(long userId, long planId, String status, String startedAt) {
        return jdbc.queryForObject("INSERT INTO workout(user_id,training_plan_id,name,status,started_at) "
                + "VALUES (?,?,'Metric workout',?," + startedAt + ") RETURNING id",
                Long.class, userId, planId, status);
    }

    private void feedback(long userId, long workoutId, int pain) {
        jdbc.update("INSERT INTO workout_feedback(workout_id,user_id,fatigue_score,pain_score) VALUES (?,?,5,?)",
                workoutId, userId, pain);
    }

    private void analytics(long userId, long workoutId, String completedAt, String volume) {
        jdbc.update("INSERT INTO workout_analytics_projection(workout_id,user_id,completed_at,duration_seconds," +
                        "training_volume,completed_set_count) VALUES (?,? ," + completedAt + ",3600,?,10)",
                workoutId, userId, new java.math.BigDecimal(volume));
    }
}
