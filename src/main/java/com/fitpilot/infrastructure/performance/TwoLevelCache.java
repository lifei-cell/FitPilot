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
    private static final L2Read UNAVAILABLE = new L2Read(null, false);
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
        L2Read l2 = redisGet(key);
        if (!l2.available()) return loadFromSource(loader);
        if (l2.payload() != null) return l2.payload();
        String lockKey = "fitpilot:v1:lock:cache:" + namespace + ":" + id;
        var handle = locks.tryLock(lockKey, Duration.ofSeconds(properties.cache().rebuildLockSeconds()));
        if (handle.isPresent()) {
            try (var ignored = handle.get()) {
                L2Read secondCheck = redisGet(key);
                if (!secondCheck.available()) return loadFromSource(loader);
                if (secondCheck.payload() != null) return secondCheck.payload();
                String payload = loadFromSource(loader);
                redisPut(key, payload);
                return payload;
            }
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            LockSupport.parkNanos(Duration.ofMillis(20L * (attempt + 1)).toNanos());
            L2Read rebuilt = redisGet(key);
            if (!rebuilt.available()) break;
            if (rebuilt.payload() != null) return rebuilt.payload();
        }
        return loadFromSource(loader);
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

    private L2Read redisGet(String key) {
        return failures.tryOptionalCall(log, "cache-read",
                () -> new L2Read(redis.opsForValue().get(key), true), UNAVAILABLE);
    }

    private <T> String loadFromSource(Supplier<Optional<T>> loader) {
        return loader.get().map(this::encode).orElse(NULL);
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

    private record L2Read(String payload, boolean available) {}

    public record CacheStatsView(long requests, long hits, long misses, double hitRate,
                                 long evictions, long estimatedSize) {}
}
