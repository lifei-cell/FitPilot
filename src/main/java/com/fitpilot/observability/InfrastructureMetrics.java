package com.fitpilot.observability;

import com.fitpilot.infrastructure.events.OutboxRepository;
import com.fitpilot.infrastructure.performance.TwoLevelCache;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class InfrastructureMetrics {
    private final AtomicLong outboxOldestSeconds = new AtomicLong();
    private final AtomicLong outboxPending = new AtomicLong();
    private final OutboxRepository outbox;

    public InfrastructureMetrics(MeterRegistry registry, TwoLevelCache cache, OutboxRepository outbox) {
        this.outbox = outbox;
        registry.gauge("fitpilot.cache.hit.rate", cache, value -> value.stats().hitRate());
        registry.gauge("fitpilot.cache.size", cache, value -> value.stats().estimatedSize());
        registry.gauge("fitpilot.outbox.oldest.pending.seconds", outboxOldestSeconds);
        registry.gauge("fitpilot.outbox.pending", outboxPending);
        refresh();
    }

    @Scheduled(initialDelay = 5_000, fixedDelay = 10_000)
    void refresh() {
        outboxOldestSeconds.set(outbox.oldestPendingAgeSeconds());
        outboxPending.set(outbox.pendingCount());
    }
}
