package com.fitpilot.agent.product;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;

@Component
public class AgentProductMetricsPublisher {
    private static final Logger log = LoggerFactory.getLogger(AgentProductMetricsPublisher.class);

    private final AgentProductMetricsService service;
    private final AgentProductMetricsProperties properties;
    private final AtomicReference<AgentProductMetricsDtos.Snapshot> latest = new AtomicReference<>();

    public AgentProductMetricsPublisher(MeterRegistry registry, AgentProductMetricsService service,
                                        AgentProductMetricsProperties properties) {
        this.service = service;
        this.properties = properties;
        gauge(registry, "fitpilot.agent.product.session.retention.eligible",
                snapshot -> snapshot.sessionRetention().eligibleUsers());
        gauge(registry, "fitpilot.agent.product.session.retention.rate",
                snapshot -> snapshot.sessionRetention().rate());
        gauge(registry, "fitpilot.agent.product.suggestion.generated",
                snapshot -> snapshot.suggestionFunnel().generated());
        gauge(registry, "fitpilot.agent.product.suggestion.acceptance.rate",
                snapshot -> snapshot.suggestionFunnel().acceptanceRate());
        gauge(registry, "fitpilot.agent.product.suggestion.rejection.rate",
                snapshot -> snapshot.suggestionFunnel().rejectionRate());
        gauge(registry, "fitpilot.agent.product.suggestion.confirmation.conversion.rate",
                snapshot -> snapshot.suggestionFunnel().confirmationConversionRate());
        gauge(registry, "fitpilot.agent.product.execution.count",
                snapshot -> snapshot.reliability().executions());
        gauge(registry, "fitpilot.agent.product.rule.fallback.rate",
                snapshot -> snapshot.reliability().ruleFallbackRate());
        gauge(registry, "fitpilot.agent.product.cost.per.success.usd",
                snapshot -> snapshot.reliability().costPerSuccessfulExecutionUsd().doubleValue());
        gauge(registry, "fitpilot.agent.product.outcome.eligible",
                snapshot -> snapshot.trainingOutcome().eligibleAdjustments());
        gauge(registry, "fitpilot.agent.product.outcome.completion.delta",
                snapshot -> snapshot.trainingOutcome().delta().completionRate());
        gauge(registry, "fitpilot.agent.product.outcome.pain.delta",
                snapshot -> snapshot.trainingOutcome().delta().averagePain());
        gauge(registry, "fitpilot.agent.product.outcome.volume.rate",
                snapshot -> snapshot.trainingOutcome().delta().trainingVolumeRate());
        gauge(registry, "fitpilot.agent.product.outcome.personal.records.delta",
                snapshot -> snapshot.trainingOutcome().delta().personalRecords());
    }

    @Scheduled(initialDelayString = "${fitpilot.agent.product-metrics.initial-delay-ms:15000}",
            fixedDelayString = "${fitpilot.agent.product-metrics.refresh-delay-ms:300000}")
    public void refresh() {
        try {
            latest.set(service.snapshot(properties.getWindowDays(), properties.getOutcomeWindowDays()));
        } catch (RuntimeException exception) {
            log.warn("Failed to refresh Agent product metrics; keeping the last successful snapshot", exception);
        }
    }

    private void gauge(MeterRegistry registry, String name,
                       ToDoubleFunction<AgentProductMetricsDtos.Snapshot> extractor) {
        Gauge.builder(name, latest, reference -> {
            AgentProductMetricsDtos.Snapshot snapshot = reference.get();
            return snapshot == null ? 0 : extractor.applyAsDouble(snapshot);
        }).register(registry);
    }
}
