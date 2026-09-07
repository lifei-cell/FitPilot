package com.fitpilot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FitPilotRedisDegradationIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("security.jwt.secret", () -> "redis-degradation-secret-key-with-32-bytes");
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> "1");
        registry.add("spring.data.redis.connect-timeout", () -> "100ms");
        registry.add("spring.data.redis.timeout", () -> "100ms");
        registry.add("fitpilot.events.enabled", () -> "false");
        registry.add("fitpilot.rag.enabled", () -> "false");
        registry.add("fitpilot.operations.token", () -> "redis-drill-token");
    }

    @Autowired MockMvc mvc;

    @Test
    void keepsReadinessUpAndServesConcurrentDatabaseFallbackTraffic() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));

        Instant started = Instant.now();
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<Integer>> responses = new ArrayList<>();
            for (int id = 1; id <= 24; id++) {
                int exerciseId = id;
                responses.add(pool.submit(() -> mvc.perform(get("/api/v1/exercises/{id}", exerciseId))
                        .andReturn().getResponse().getStatus()));
            }
            for (Future<Integer> response : responses) assertThat(response.get()).isEqualTo(200);
        }
        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(15));
    }
}
