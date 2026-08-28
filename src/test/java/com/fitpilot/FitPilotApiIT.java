package com.fitpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.plan.application.TrainingPlanService;
import com.fitpilot.plan.dto.TrainingPlanDtos;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class FitPilotApiIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("security.jwt.secret", () -> "integration-test-secret-key-with-32-bytes-minimum");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TrainingPlanService trainingPlanService;
    @Autowired JdbcTemplate jdbc;

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

        JsonNode workout = call(post("/api/v1/workouts").header("Authorization", bearer(token))
                .contentType("application/json").content(json.writeValueAsBytes(Map.of(
                        "trainingPlanId", planId, "trainingPlanDayId", dayId))), 201);
        long workoutId = workout.path("data").path("id").asLong();
        long workoutExerciseId = workout.path("data").path("exercises").get(0).path("id").asLong();
        call(post("/api/v1/workouts/{workoutId}/exercises/{exerciseId}/sets", workoutId, workoutExerciseId)
                .header("Authorization", bearer(token)).contentType("application/json")
                .content("{\"weightKg\":80,\"reps\":5,\"rpe\":8,\"isWarmup\":false}"), 201);

        JsonNode completed = call(post("/api/v1/workouts/{id}/complete", workoutId)
                .header("Authorization", bearer(token)), 200);
        assertThat(completed.path("data").path("workout").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(completed.path("data").path("newPersonalRecords").asInt()).isEqualTo(4);
        JsonNode repeated = call(post("/api/v1/workouts/{id}/complete", workoutId)
                .header("Authorization", bearer(token)), 200);
        assertThat(repeated.path("data").path("newPersonalRecords").asInt()).isEqualTo(4);

        mvc.perform(get("/api/v1/personal-records").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(4));
        mvc.perform(get("/api/v1/analytics/overview").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.trainingVolume").value(400));

        register("other_user");
        String otherToken = login("other_user");
        mvc.perform(get("/api/v1/workouts/{id}", workoutId).header("Authorization", bearer(otherToken)))
                .andExpect(status().isNotFound());
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
