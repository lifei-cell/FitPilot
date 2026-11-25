package com.fitpilot.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class FitPilotMetricsTest {
    @Test
    void keepsOtlpTracingExportOptIn() throws IOException {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }

        assertThat(environment.getProperty("management.tracing.export.otlp.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("management.opentelemetry.tracing.export.otlp.endpoint"))
                .isEqualTo("http://localhost:4318/v1/traces");
    }

    @Test
    void recordsProductionAgentRagLlmToolAndOutboxMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FitPilotMetrics metrics = new FitPilotMetrics(registry);

        metrics.agent("SUCCEEDED", false, 25, 0);
        metrics.tool("get_user_profile", "SUCCEEDED", 5);
        metrics.rag("HYBRID_RRF", "SUCCEEDED", 10);
        metrics.llm("primary", "small", "SUCCEEDED", 100, 20, 10,
                new BigDecimal("0.0001"));
        metrics.outbox("SENT");

        assertThat(registry.get("fitpilot.agent.requests").counter().count()).isEqualTo(1);
        assertThat(registry.get("fitpilot.agent.tool.calls").counter().count()).isEqualTo(1);
        assertThat(registry.get("fitpilot.rag.requests").counter().count()).isEqualTo(1);
        assertThat(registry.get("fitpilot.llm.tokens").tag("type", "input").counter().count()).isEqualTo(20);
        assertThat(registry.get("fitpilot.outbox.publish").counter().count()).isEqualTo(1);
    }
}
