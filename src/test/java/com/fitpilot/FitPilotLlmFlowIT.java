package com.fitpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker=true)
@DirtiesContext(classMode= DirtiesContext.ClassMode.AFTER_CLASS)
class FitPilotLlmFlowIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    static final AtomicBoolean FAIL_FALLBACK=new AtomicBoolean();
    static final HttpServer PRIMARY=start(true);
    static final HttpServer FALLBACK=start(false);

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);registry.add("spring.datasource.username",POSTGRES::getUsername);registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("spring.data.redis.host",REDIS::getHost);registry.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        registry.add("security.jwt.secret",()->"integration-test-secret-key-with-32-bytes-minimum");registry.add("fitpilot.events.enabled",()->"false");registry.add("fitpilot.rag.enabled",()->"false");registry.add("fitpilot.performance.rate-limit.enabled",()->"false");
        registry.add("fitpilot.operations.token",()->"ops-token");registry.add("fitpilot.llm.enabled",()->"true");registry.add("fitpilot.llm.max-retries",()->"0");
        registry.add("fitpilot.llm.primary.url",()->url(PRIMARY));registry.add("fitpilot.llm.primary.small-model",()->"primary-small");registry.add("fitpilot.llm.primary.medium-model",()->"primary-medium");registry.add("fitpilot.llm.primary.strong-model",()->"primary-strong");
        registry.add("fitpilot.llm.fallback.url",()->url(FALLBACK));registry.add("fitpilot.llm.fallback.small-model",()->"fallback-small");registry.add("fitpilot.llm.fallback.medium-model",()->"fallback-medium");registry.add("fitpilot.llm.fallback.strong-model",()->"fallback-strong");
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;

    @Test void primaryFallbackAndRuleFallbackRemainSafeAndAudited() throws Exception {
        String token=registerAndLogin("llm_user");String session=session(token);
        JsonNode modelResponse=call(post("/api/v1/agent/sessions/"+session+"/messages").header("Authorization",bearer(token)).contentType("application/json").content(json.writeValueAsBytes(Map.of("message","分析我的训练量"))),200);
        assertThat(modelResponse.path("data").path("selectedTools").path(0).asText()).isEqualTo("get_training_volume");
        assertThat(modelResponse.path("data").path("model").asText()).isEqualTo("fallback-medium");
        assertThat(modelResponse.path("data").path("degraded").asBoolean()).isTrue();
        assertThat(modelResponse.path("data").path("answer").asText()).isEqualTo("模型分析完成");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_invocation",Long.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT degraded FROM agent_execution ORDER BY created_at DESC LIMIT 1",Boolean.class)).isTrue();

        FAIL_FALLBACK.set(true);String second=session(token);
        JsonNode ruleResponse=call(post("/api/v1/agent/sessions/"+second+"/messages").header("Authorization",bearer(token)).contentType("application/json").content(json.writeValueAsBytes(Map.of("message","查看用户画像"))),200);
        assertThat(ruleResponse.path("data").path("model").asText()).isEqualTo("RULE_WORKFLOW");
        assertThat(ruleResponse.path("data").path("degraded").asBoolean()).isTrue();
        mvc.perform(get("/api/v1/operations/llm/status").header("X-Operations-Token","ops-token")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/operations/llm/invocations").header("X-Operations-Token","wrong")).andExpect(status().isForbidden());
    }
    private String session(String token)throws Exception{return call(post("/api/v1/agent/sessions").header("Authorization",bearer(token)),201).path("data").path("id").asText();}
    private String registerAndLogin(String username)throws Exception{call(post("/api/v1/auth/register").contentType("application/json").content(json.writeValueAsBytes(Map.of("username",username,"email",username+"@example.com","password","password123"))),201);return call(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsBytes(Map.of("username",username,"password","password123"))),200).path("data").path("accessToken").asText();}
    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,int status)throws Exception{return json.readTree(mvc.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(status)).andReturn().getResponse().getContentAsString());}
    private String bearer(String token){return "Bearer "+token;}
    private static HttpServer start(boolean primary){try{HttpServer server=HttpServer.create(new InetSocketAddress("localhost",0),0);server.createContext("/chat",exchange->respond(exchange,primary));server.start();return server;}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    private static void respond(HttpExchange exchange,boolean primary)throws IOException{byte[] request=exchange.getRequestBody().readAllBytes();if(primary||FAIL_FALLBACK.get()){exchange.sendResponseHeaders(503,-1);exchange.close();return;}String text=new String(request,StandardCharsets.UTF_8);String content=text.contains("FitPilot's planner")?"{\"intent\":\"TRAINING_VOLUME\",\"toolCalls\":[{\"name\":\"get_training_volume\",\"arguments\":{}}],\"responseMode\":\"analysis\"}":"模型分析完成";String escaped=new ObjectMapper().writeValueAsString(content);byte[] body=("{\"choices\":[{\"message\":{\"content\":"+escaped+"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":4}}").getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();}
    private static String url(HttpServer server){return "http://localhost:"+server.getAddress().getPort()+"/chat";}
    @AfterAll static void stop(){PRIMARY.stop(0);FALLBACK.stop(0);}
}
