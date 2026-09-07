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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test void retriesRetryableFailureTwiceAndRecordsProviderTokens() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/primary",exchange->{
            if(requests.incrementAndGet()<3){exchange.sendResponseHeaders(429,-1);exchange.close();return;}
            byte[] body="{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":3}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        LlmProperties properties=new LlmProperties();properties.setEnabled(true);properties.setMaxRetries(2);
        configure(properties.getPrimary(),"primary","http://localhost:"+server.getAddress().getPort()+"/primary");
        properties.getPrimary().setInputCostPerMillion(1);properties.getPrimary().setOutputCostPerMillion(2);
        var client=new OpenAiCompatibleClient(properties,new ModelRouter(),new PromptRegistry(properties),
                new SensitiveDataRedactor(),new ObjectMapper(),mock(LlmInvocationRepository.class),mock(FitPilotMetrics.class));
        var result=client.complete(UUID.randomUUID(),LlmModels.Task.INTENT_CLASSIFICATION,"retry",true);
        assertThat(requests).hasValue(3);
        assertThat(result.inputTokens()).isEqualTo(11);
        assertThat(result.outputTokens()).isEqualTo(3);
        assertThat(result.costUsd()).isEqualByComparingTo("0.00001700");
        assertThat(result.degraded()).isTrue();
    }

    @Test void enforcesOneDeadlineAcrossRetriesAndFallback() throws Exception {
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/slow",exchange->{
            try { Thread.sleep(3000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.sendResponseHeaders(503,-1);exchange.close();
        });
        server.start();
        LlmProperties properties=new LlmProperties();properties.setEnabled(true);properties.setMaxRetries(2);
        properties.setRequestTimeoutSeconds(5);properties.setTotalTimeoutSeconds(1);
        configure(properties.getPrimary(),"primary","http://localhost:"+server.getAddress().getPort()+"/slow");
        configure(properties.getFallback(),"fallback","http://localhost:"+server.getAddress().getPort()+"/slow");
        var client=client(properties);

        long started=System.nanoTime();
        assertThatThrownBy(()->client.complete(UUID.randomUUID(),LlmModels.Task.TRAINING_ANALYSIS,"slow",false))
                .isInstanceOf(OpenAiCompatibleClient.LlmUnavailableException.class)
                .hasMessageContaining("deadline");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)).isLessThan(2000);
    }

    @Test void rejectsAboveBulkheadConcurrencyWithoutWaitingForProvider() throws Exception {
        CountDownLatch entered=new CountDownLatch(1);CountDownLatch release=new CountDownLatch(1);
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/blocked",exchange->{
            entered.countDown();
            try { release.await(3,TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            byte[] body="{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        LlmProperties properties=new LlmProperties();properties.setEnabled(true);properties.setMaxRetries(0);
        properties.setMaxConcurrentRequests(1);properties.setBulkheadAcquireTimeoutMs(20);
        configure(properties.getPrimary(),"primary","http://localhost:"+server.getAddress().getPort()+"/blocked");
        var client=client(properties);
        CompletableFuture<?> first=CompletableFuture.supplyAsync(()->client.complete(
                UUID.randomUUID(),LlmModels.Task.INTENT_CLASSIFICATION,"first",false));
        assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(()->client.complete(UUID.randomUUID(),LlmModels.Task.INTENT_CLASSIFICATION,"second",false))
                .isInstanceOf(OpenAiCompatibleClient.LlmUnavailableException.class)
                .hasMessageContaining("bulkhead");
        release.countDown();
        first.get(2,TimeUnit.SECONDS);
    }

    @Test void honorsAndCapsRetryAfter() throws Exception {
        AtomicInteger requests=new AtomicInteger();
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/retry-after",exchange->{
            if(requests.incrementAndGet()==1){exchange.getResponseHeaders().add("Retry-After","2");exchange.sendResponseHeaders(429,-1);exchange.close();return;}
            byte[] body="{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        LlmProperties properties=new LlmProperties();properties.setEnabled(true);properties.setMaxRetries(1);
        properties.setMaxRetryAfterMs(250);
        configure(properties.getPrimary(),"primary","http://localhost:"+server.getAddress().getPort()+"/retry-after");
        var client=client(properties);

        long started=System.nanoTime();
        client.complete(UUID.randomUUID(),LlmModels.Task.INTENT_CLASSIFICATION,"retry",false);
        long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
        assertThat(elapsed).isBetween(200L,1000L);
        assertThat(requests).hasValue(2);
    }

    @Test void allowsHalfOpenProbeAndClosesCircuitAfterSuccess() throws Exception {
        AtomicInteger requests=new AtomicInteger();
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/circuit",exchange->{
            if(requests.incrementAndGet()==1){exchange.sendResponseHeaders(503,-1);exchange.close();return;}
            byte[] body="{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        LlmProperties properties=new LlmProperties();properties.setEnabled(true);properties.setMaxRetries(0);
        properties.setCircuitFailureThreshold(1);properties.setCircuitOpenSeconds(1);
        configure(properties.getPrimary(),"primary","http://localhost:"+server.getAddress().getPort()+"/circuit");
        var client=client(properties);

        assertThatThrownBy(()->client.complete(UUID.randomUUID(),LlmModels.Task.INTENT_CLASSIFICATION,"open",false))
                .isInstanceOf(OpenAiCompatibleClient.LlmUnavailableException.class);
        assertThatThrownBy(()->client.complete(UUID.randomUUID(),LlmModels.Task.INTENT_CLASSIFICATION,"denied",false))
                .isInstanceOf(OpenAiCompatibleClient.LlmUnavailableException.class);
        assertThat(requests).hasValue(1);
        Thread.sleep(1100);
        assertThat(client.complete(UUID.randomUUID(),LlmModels.Task.INTENT_CLASSIFICATION,"probe",false).content())
                .isEqualTo("ok");
        assertThat(client.status().get("primaryCircuit")).isEqualTo("CLOSED");
        assertThat(requests).hasValue(2);
    }

    @Test void propagatesThreadCancellationToInFlightHttpCall() throws Exception {
        CountDownLatch entered=new CountDownLatch(1);CountDownLatch release=new CountDownLatch(1);
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/cancel",exchange->{
            entered.countDown();
            try { release.await(3,TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.sendResponseHeaders(503,-1);exchange.close();
        });
        server.start();
        LlmProperties properties=new LlmProperties();properties.setEnabled(true);properties.setMaxRetries(2);
        configure(properties.getPrimary(),"primary","http://localhost:"+server.getAddress().getPort()+"/cancel");
        var client=client(properties);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread caller=new Thread(()->{
            try { client.complete(UUID.randomUUID(),LlmModels.Task.INTENT_CLASSIFICATION,"cancel",false); }
            catch (Throwable thrown) { failure.set(thrown); }
        });
        caller.start();
        assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();

        caller.interrupt();
        caller.join(1500);
        release.countDown();
        assertThat(caller.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(OpenAiCompatibleClient.LlmUnavailableException.class)
                .hasMessageContaining("cancelled");
        assertThat(client.status().get("availablePermits")).isEqualTo(16);
    }

    private OpenAiCompatibleClient client(LlmProperties properties){
        return new OpenAiCompatibleClient(properties,new ModelRouter(),new PromptRegistry(properties),
                new SensitiveDataRedactor(),new ObjectMapper(),mock(LlmInvocationRepository.class),mock(FitPilotMetrics.class));
    }
    private void configure(LlmProperties.Endpoint endpoint,String name,String url){endpoint.setName(name);endpoint.setUrl(url);endpoint.setSmallModel("small");endpoint.setMediumModel("medium");endpoint.setStrongModel("strong");}
}
