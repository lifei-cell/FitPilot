package com.fitpilot.infrastructure.performance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

@Component
public class TwoLevelCache {
    private static final Logger log = LoggerFactory.getLogger(TwoLevelCache.class);
    private static final String NULL = "__FITPILOT_NULL__";
    private final Cache<String, String> local;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final DistributedLockService locks;
    private final PerformanceProperties properties;
    private final RedisFailureGuard failures = new RedisFailureGuard();

    public TwoLevelCache(Cache<String, String> hotDataLocalCache, StringRedisTemplate redis,
                         ObjectMapper objectMapper, DistributedLockService locks,
                         PerformanceProperties properties) {
        this.local = hotDataLocalCache;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.locks = locks;
        this.properties = properties;
    }

    public <T> Optional<T> get(String namespace, String id, Class<T> type, Supplier<Optional<T>> loader) {
        if (!properties.cache().enabled()) return loader.get();
        String key = key(namespace, id);
        String payload = local.get(key, ignored -> loadPayload(namespace, id, key, loader));
        try { return decode(payload, type); }
        catch (IllegalStateException corrupted) {
            log.warn("Invalid cache payload evicted; key={}", key);
            evict(namespace, id);
            Optional<T> loaded = loader.get();
            String rebuilt = loaded.map(this::encode).orElse(NULL);
            local.put(key, rebuilt);
            redisPut(key, rebuilt);
            return loaded;
        }
    }

    private <T> String loadPayload(String namespace, String id, String key, Supplier<Optional<T>> loader) {
        String l2 = redisGet(key);
        if (l2 != null) return l2;
        String lockKey = "fitpilot:v1:lock:cache:" + namespace + ":" + id;
        var handle = locks.tryLock(lockKey, Duration.ofSeconds(properties.cache().rebuildLockSeconds()));
        if (handle.isPresent()) {
            try (var ignored = handle.get()) {
                String secondCheck = redisGet(key);
                if (secondCheck != null) return secondCheck;
                Optional<T> loaded = loader.get();
                String payload = loaded.map(this::encode).orElse(NULL);
                redisPut(key, payload);
                return payload;
            }
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            LockSupport.parkNanos(Duration.ofMillis(20L * (attempt + 1)).toNanos());
            String rebuilt = redisGet(key);
            if (rebuilt != null) return rebuilt;
        }
        Optional<T> loaded = loader.get();
        return loaded.map(this::encode).orElse(NULL);
    }

    public void evict(String namespace, String id) {
        String key = key(namespace, id);
        local.invalidate(key);
        try { redis.delete(key); }
        catch (RuntimeException ex) { failures.warn(log, "cache-evict", ex); }
    }

    public void evictAfterCommit(String namespace, String id) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { evict(namespace, id); }
            });
        } else evict(namespace, id);
    }

    public CacheStatsView stats() {
        var stats = local.stats();
        return new CacheStatsView(stats.requestCount(), stats.hitCount(), stats.missCount(), stats.hitRate(),
                stats.evictionCount(), local.estimatedSize());
    }

    private String redisGet(String key) {
        try { return redis.opsForValue().get(key); }
        catch (RuntimeException ex) { failures.warn(log, "cache-read", ex); return null; }
    }

    private void redisPut(String key, String payload) {
        long jitter = properties.cache().l2TtlJitterSeconds();
        long ttl = properties.cache().l2TtlSeconds()
                + (jitter == 0 ? 0 : ThreadLocalRandom.current().nextLong(jitter + 1));
        try { redis.opsForValue().set(key, payload, Duration.ofSeconds(ttl)); }
        catch (RuntimeException ex) { failures.warn(log, "cache-write", ex); }
    }

    private <T> Optional<T> decode(String payload, Class<T> type) {
        if (NULL.equals(payload)) return Optional.empty();
        try { return Optional.of(objectMapper.readValue(payload, type)); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("invalid cached payload", ex); }
    }

    private String encode(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("cannot encode cache value", ex); }
    }

    private String key(String namespace, String id) { return "fitpilot:v1:cache:" + namespace + ":" + id; }

    public record CacheStatsView(long requests, long hits, long misses, double hitRate,
                                 long evictions, long estimatedSize) {}
}
