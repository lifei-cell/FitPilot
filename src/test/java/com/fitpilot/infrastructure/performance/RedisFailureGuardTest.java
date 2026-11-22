package com.fitpilot.infrastructure.performance;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class RedisFailureGuardTest {
    private static final long SECOND = Duration.ofSeconds(1).toNanos();
    private final AtomicLong clock = new AtomicLong();
    private final RedisFailureGuard guard = new RedisFailureGuard(clock::get);

    @Test
    void retriesAfterCooldownAndStaysDegradedWhenProbeFails() {
        assertThat(call(this::disconnected)).isEqualTo("fallback");
        clock.set(SECOND - 1);
        assertThat(call(() -> "unexpected early read")).isEqualTo("fallback");
        clock.set(SECOND);
        assertThat(call(this::disconnected)).isEqualTo("fallback");
        clock.set(2 * SECOND - 1);
        assertThat(call(() -> "unexpected early retry")).isEqualTo("fallback");
        clock.set(2 * SECOND);
        assertThat(call(() -> "probe succeeded")).isEqualTo("probe succeeded");
        assertThat(call(() -> "normal read")).isEqualTo("normal read");
    }

    @Test
    void concurrentRequestsUseFallbackWhileTheSingleProbeIsInFlight() throws Exception {
        assertThat(call(this::disconnected)).isEqualTo("fallback");
        clock.set(SECOND);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var probe = executor.submit(() -> call(() -> {
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("probe not released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return "probe";
            }));
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(call(() -> "second probe")).isEqualTo("fallback");
            } finally {
                release.countDown();
            }
            assertThat(probe.get(5, TimeUnit.SECONDS)).isEqualTo("probe");
        }
        assertThat(call(() -> "normal read")).isEqualTo("normal read");
    }

    private String call(Supplier<String> operation) {
        return guard.tryOptionalCall(LoggerFactory.getLogger(getClass()), "fixture", operation, "fallback");
    }

    private String disconnected() { throw new RedisConnectionFailureException("fixture outage"); }
}
