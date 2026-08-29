package com.fitpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FitPilotRagFlowIT {
    private static final String OPERATIONS_TOKEN = "rag-integration-operations-token";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    @Container
    static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            "docker.elastic.co/elasticsearch/elasticsearch:8.17.4")
            .withEnv("xpack.security.enabled", "false");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("security.jwt.secret", () -> "integration-test-secret-key-with-32-bytes-minimum");
        registry.add("fitpilot.events.enabled", () -> "false");
        registry.add("fitpilot.performance.rate-limit.enabled", () -> "false");
        registry.add("fitpilot.rag.enabled", () -> "true");
        registry.add("fitpilot.rag.operations-token", () -> OPERATIONS_TOKEN);
        registry.add("fitpilot.rag.elasticsearch.url", () -> "http://" + ELASTICSEARCH.getHttpHostAddress());
        registry.add("fitpilot.rag.elasticsearch.index", () -> "fitpilot-rag-it");
        registry.add("fitpilot.rag.indexing.fixed-delay-ms", () -> "600000");
        registry.add("fitpilot.rag.chunking.parent-max-chars", () -> "500");
        registry.add("fitpilot.rag.chunking.child-max-chars", () -> "180");
        registry.add("fitpilot.rag.chunking.child-overlap-chars", () -> "30");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void ingestsSearchesReindexesAndDeletesKnowledgeWithCitations() throws Exception {
        String token = registerAndLogin("rag_user");
        JsonNode ingested = call(post("/api/v1/operations/rag/documents")
                .header("X-Operations-Token", OPERATIONS_TOKEN).contentType("application/json")
                .content(json.writeValueAsBytes(Map.of(
                        "externalId", "fitpilot:rpe-guide", "title", "RPE 与 RIR 训练指南",
                        "category", "training-theory", "sourceUrl", "https://example.org/fitness/rpe-guide",
                        "sourceLicense", "CC-BY-4.0", "format", "MARKDOWN", "content", """
                        # RPE 与 RIR
                        RPE 是主观用力程度。RPE 8 通常表示这一组结束时还保留大约两次重复，也就是 RIR 2。
                        训练者应优先维持动作技术，再根据当天状态小幅调整负重。

                        # 减量周
                        Deload 减量周可通过降低训练组数、负重或接近力竭程度来管理累积疲劳，并促进恢复。
                        """))), 201);
        String documentId = ingested.path("data").path("id").asText();
        assertThat(ingested.path("data").path("indexStatus").asText()).isEqualTo("INDEXED");
        assertThat(ingested.path("data").path("parentChunks").asInt()).isEqualTo(2);
        assertThat(ingested.path("data").path("childChunks").asInt()).isGreaterThanOrEqualTo(2);

        JsonNode result = call(get("/api/v1/rag/search")
                .header("Authorization", bearer(token)).param("q", "RPE 8 还剩多少次重复")
                .param("category", "training-theory").param("topK", "3"), 200);
        assertThat(result.path("data").path("retrievalMode").asText()).isEqualTo("HYBRID_RRF");
        JsonNode first = result.path("data").path("contexts").get(0);
        assertThat(first.path("content").asText()).contains("RIR 2");
        assertThat(first.path("citation").path("sourceLicense").asText()).isEqualTo("CC-BY-4.0");
        assertThat(first.path("matchedBy")).extracting(JsonNode::asText).contains("BM25", "VECTOR");

        mvc.perform(get("/api/v1/operations/rag/documents").header("X-Operations-Token", "wrong"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1002));
        call(post("/api/v1/operations/rag/documents/{id}/reindex", documentId)
                .header("X-Operations-Token", OPERATIONS_TOKEN), 200);
        call(delete("/api/v1/operations/rag/documents/{id}", documentId)
                .header("X-Operations-Token", OPERATIONS_TOKEN), 200);
        JsonNode empty = call(get("/api/v1/rag/search").header("Authorization", bearer(token))
                .param("q", "RPE 8"), 200);
        assertThat(empty.path("data").path("retrievalMode").asText()).isEqualTo("HYBRID_RRF");
        assertThat(empty.path("data").path("contexts")).isEmpty();
    }

    private String registerAndLogin(String username) throws Exception {
        call(post("/api/v1/auth/register").contentType("application/json").content(json.writeValueAsBytes(
                Map.of("username", username, "email", username + "@example.com", "password", "password123"))), 201);
        return call(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsBytes(
                Map.of("username", username, "password", "password123"))), 200)
                .path("data").path("accessToken").asText();
    }

    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                          int expectedStatus) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsByteArray());
    }

    private String bearer(String token) { return "Bearer " + token; }
}
