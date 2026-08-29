package com.fitpilot.llm.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.llm.application.ModelRouter;
import com.fitpilot.llm.application.PromptRegistry;
import com.fitpilot.llm.config.LlmProperties;
import com.fitpilot.llm.domain.LlmModels;
import com.fitpilot.llm.security.SensitiveDataRedactor;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import com.fitpilot.observability.FitPilotMetrics;

class OpenAiCompatibleClientTest {
    private HttpServer server;
    @AfterEach void stop(){if(server!=null)server.stop(0);}

    @Test void fallsBackAfterRetryablePrimaryFailure() throws Exception {
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/primary",exchange->{exchange.sendResponseHeaders(503,-1);exchange.close();});
        server.createContext("/fallback",exchange->{byte[] body="{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
        server.start();
        LlmProperties properties=new LlmProperties();properties.setEnabled(true);properties.setMaxRetries(0);
        configure(properties.getPrimary(),"primary","http://localhost:"+server.getAddress().getPort()+"/primary");
        configure(properties.getFallback(),"fallback","http://localhost:"+server.getAddress().getPort()+"/fallback");
        var client=new OpenAiCompatibleClient(properties,new ModelRouter(),new PromptRegistry(properties),
                new SensitiveDataRedactor(),new ObjectMapper(),mock(LlmInvocationRepository.class),mock(FitPilotMetrics.class));
        var result=client.complete(UUID.randomUUID(),LlmModels.Task.TRAINING_ANALYSIS,"email a@b.com",false);
        assertThat(result.content()).isEqualTo("ok");
        assertThat(result.provider()).isEqualTo("fallback");
        assertThat(result.degraded()).isTrue();
    }
    private void configure(LlmProperties.Endpoint endpoint,String name,String url){endpoint.setName(name);endpoint.setUrl(url);endpoint.setSmallModel("small");endpoint.setMediumModel("medium");endpoint.setStrongModel("strong");}
}
