package com.fitpilot.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class FitPilotMetricsTest {
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
