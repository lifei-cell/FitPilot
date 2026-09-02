package com.fitpilot.agent.product;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentProductMetricsPublisherTest {
    @Test
    void publishesLatestSnapshotAsLowCardinalityGauges() {
        var registry = new SimpleMeterRegistry();
        var service = mock(AgentProductMetricsService.class);
        var properties = new AgentProductMetricsProperties();
        var snapshot = new AgentProductMetricsDtos.Snapshot(Instant.now(), 28, 28,
                new AgentProductMetricsDtos.SessionRetention(10, 4, 0.4, "D7"),
                new AgentProductMetricsDtos.SuggestionFunnel(8, 3, 1, 2, 2, 0.75, 0.25, 0.375),
                new AgentProductMetricsDtos.Reliability(5, 4, 1, 0.2,
                        new BigDecimal("0.12"), new BigDecimal("0.03")),
                List.of(),
                new AgentProductMetricsDtos.TrainingOutcome(28, 2,
                        new AgentProductMetricsDtos.TrainingPeriod(4, 2, 2, 0.5, 4, 3.5,
                                new BigDecimal("1000"), 1),
                        new AgentProductMetricsDtos.TrainingPeriod(4, 3, 1, 0.75, 4, 1.5,
                                new BigDecimal("1500"), 3),
                        new AgentProductMetricsDtos.TrainingDelta(0.25, -2,
                                new BigDecimal("500"), 0.5, 2), List.of()),
                new AgentProductMetricsDtos.MetricDefinitions("D7", "a", "r", "c", "f", "cost", "outcome"));
        when(service.snapshot(28, 28)).thenReturn(snapshot);

        var publisher = new AgentProductMetricsPublisher(registry, service, properties);
        publisher.refresh();

        assertThat(registry.get("fitpilot.agent.product.session.retention.rate").gauge().value())
                .isEqualTo(0.4);
        assertThat(registry.get("fitpilot.agent.product.suggestion.confirmation.conversion.rate").gauge().value())
                .isEqualTo(0.375);
        assertThat(registry.get("fitpilot.agent.product.rule.fallback.rate").gauge().value())
                .isEqualTo(0.2);
        assertThat(registry.get("fitpilot.agent.product.cost.per.success.usd").gauge().value())
                .isEqualTo(0.03);
        assertThat(registry.get("fitpilot.agent.product.outcome.pain.delta").gauge().value())
                .isEqualTo(-2);
    }
}
