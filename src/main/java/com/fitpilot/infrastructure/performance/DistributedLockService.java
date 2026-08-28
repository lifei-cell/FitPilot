package com.fitpilot.infrastructure.performance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class DistributedLockService {
    private static final Logger log = LoggerFactory.getLogger(DistributedLockService.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);
    private final StringRedisTemplate redis;
    private final RedisFailureGuard failures = new RedisFailureGuard();

    public DistributedLockService(StringRedisTemplate redis) { this.redis = redis; }

    public Optional<LockHandle> tryLock(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        try {
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, ttl))) {
                return Optional.of(new LockHandle(key, token));
            }
        } catch (RuntimeException ex) {
            failures.warn(log, "distributed-lock", ex);
        }
        return Optional.empty();
    }

    public final class LockHandle implements AutoCloseable {
        private final String key;
        private final String token;
        private boolean closed;

        private LockHandle(String key, String token) { this.key = key; this.token = token; }

        @Override public void close() {
            if (closed) return;
            closed = true;
            try { redis.execute(RELEASE, List.of(key), token); }
            catch (RuntimeException ex) { failures.warn(log, "distributed-unlock", ex); }
        }
    }
}
