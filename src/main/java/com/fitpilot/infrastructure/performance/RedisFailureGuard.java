package com.fitpilot.infrastructure.performance;

import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

final class RedisFailureGuard {
    private final AtomicLong nextLogAt = new AtomicLong();

    void warn(Logger log, String operation, RuntimeException failure) {
        long now = System.currentTimeMillis();
        long next = nextLogAt.get();
        if (now >= next && nextLogAt.compareAndSet(next, now + 60_000)) {
            log.warn("Redis unavailable; operation={} degraded=true reason={}", operation,
                    failure.getClass().getSimpleName());
        }
    }
}
