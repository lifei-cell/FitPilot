package com.fitpilot.infrastructure.performance;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class RedisTokenBucketRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);
    private static final DefaultRedisScript<List> SCRIPT = script();
    private final StringRedisTemplate redis;
    private final Cache<String, LocalBucket> localFallback = Caffeine.newBuilder()
            .maximumSize(100_000).expireAfterAccess(Duration.ofMinutes(10)).build();
    private final RedisFailureGuard failures = new RedisFailureGuard();

    public RedisTokenBucketRateLimiter(StringRedisTemplate redis) { this.redis = redis; }

    public Result consume(String scope, long capacity, long refillPerSecond) {
        String key = "fitpilot:v1:rate-limit:" + scope;
        long now = System.currentTimeMillis();
        try {
            List<?> result = redis.execute(SCRIPT, List.of(key), String.valueOf(capacity),
                    String.valueOf(refillPerSecond), String.valueOf(now), "1");
            if (result != null && result.size() == 2) {
                return new Result(number(result.get(0)) == 1, number(result.get(1)), false);
            }
        } catch (RuntimeException ex) {
            failures.warn(log, "rate-limit", ex);
        }
        LocalBucket bucket = localFallback.get(scope, ignored -> new LocalBucket(capacity, now));
        return bucket.consume(capacity, refillPerSecond, now);
    }

    private static long number(Object value) { return ((Number) value).longValue(); }

    private static DefaultRedisScript<List> script() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/token_bucket.lua"));
        script.setResultType(List.class);
        return script;
    }

    private static final class LocalBucket {
        private double tokens;
        private long lastRefill;

        private LocalBucket(long capacity, long now) { this.tokens = capacity; this.lastRefill = now; }

        synchronized Result consume(long capacity, long refillPerSecond, long now) {
            tokens = Math.min(capacity, tokens + Math.max(0, now - lastRefill) * refillPerSecond / 1000.0);
            lastRefill = now;
            boolean allowed = tokens >= 1;
            if (allowed) tokens--;
            return new Result(allowed, (long) tokens, true);
        }
    }

    public record Result(boolean allowed, long remaining, boolean degraded) {}
}
