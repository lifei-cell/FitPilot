package com.fitpilot.infrastructure.performance;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fitpilot.performance")
public record PerformanceProperties(Cache cache, RateLimit rateLimit, Idempotency idempotency,
                                    Leaderboard leaderboard) {
    public record Cache(boolean enabled, long l1MaximumSize, long l1TtlSeconds, long l2TtlSeconds,
                        long l2TtlJitterSeconds, long rebuildLockSeconds) {}
    public record RateLimit(boolean enabled, long apiCapacity, long apiRefillPerSecond,
                            long loginCapacity, long loginRefillPerSecond) {}
    public record Idempotency(boolean enabled, long resultTtlSeconds, long processingTtlSeconds) {}
    public record Leaderboard(long ttlSeconds) {}
}
