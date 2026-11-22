package com.fitpilot.infrastructure.performance;

import org.slf4j.Logger;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class RedisFailureGuard {
    private static final long RETRY_NANOS = Duration.ofSeconds(1).toNanos();
    private final AtomicLong nextLogAt = new AtomicLong();
    private final AtomicReference<State> state = new AtomicReference<>(new State(Mode.AVAILABLE, 0));
    private final LongSupplier clock;

    RedisFailureGuard() { this(System::nanoTime); }

    RedisFailureGuard(LongSupplier clock) { this.clock = clock; }

    <T> T tryOptionalCall(Logger log, String operation, Supplier<T> redisCall, T unavailable) {
        State attempt = state.get();
        if (attempt.mode() == Mode.PROBING) return unavailable;
        if (attempt.mode() == Mode.COOLDOWN) {
            if (clock.getAsLong() - attempt.retryAt() < 0) return unavailable;
            State probe = new State(Mode.PROBING, 0);
            if (!state.compareAndSet(attempt, probe)) return unavailable;
            attempt = probe;
        }
        try {
            return redisCall.get();
        } catch (RuntimeException failure) {
            state.compareAndSet(attempt, new State(Mode.COOLDOWN, clock.getAsLong() + RETRY_NANOS));
            warn(log, operation, failure);
            return unavailable;
        } finally {
            // Only the probe can reopen its generation. A failed probe already installed a new cooldown.
            if (attempt.mode() == Mode.PROBING) state.compareAndSet(attempt, new State(Mode.AVAILABLE, 0));
        }
    }

    void warn(Logger log, String operation, RuntimeException failure) {
        long now = System.currentTimeMillis();
        long next = nextLogAt.get();
        if (now >= next && nextLogAt.compareAndSet(next, now + 60_000)) {
            log.warn("Redis unavailable; operation={} degraded=true reason={}", operation,
                    failure.getClass().getSimpleName());
        }
    }

    private enum Mode { AVAILABLE, COOLDOWN, PROBING }

    private record State(Mode mode, long retryAt) {}
}
