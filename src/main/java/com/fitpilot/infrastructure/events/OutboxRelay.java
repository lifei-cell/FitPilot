package com.fitpilot.infrastructure.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

import com.fitpilot.observability.FitPilotMetrics;
import io.micrometer.observation.annotation.Observed;

@Component
@ConditionalOnProperty(prefix = "fitpilot.events", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final EventProperties properties;
    private final FitPilotMetrics metrics;

    public OutboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafka,
                       EventProperties properties, FitPilotMetrics metrics) {
        this.repository = repository;
        this.kafka = kafka;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${fitpilot.events.relay.fixed-delay-ms:500}")
    @Observed(name = "fitpilot.outbox.relay")
    public void relay() {
        var relay = properties.getRelay();
        for (OutboxEvent event : repository.claimBatch(relay.getBatchSize(), relay.getClaimTimeoutSeconds(),
                relay.getMaxAttempts())) {
            try {
                kafka.send(event.topic(), event.eventKey(), event.payload())
                        .get(relay.getSendTimeoutSeconds(), TimeUnit.SECONDS);
                repository.markSent(event.id());
                metrics.outbox("SENT");
            } catch (Exception failure) {
                repository.markFailed(event, failure, relay.getMaxAttempts());
                metrics.outbox("FAILED");
                log.warn("operation=OutboxPublishFailed outboxId={} topic={} attempt={}",
                        event.id(), event.topic(), event.retryCount() + 1);
            }
        }
    }
}
