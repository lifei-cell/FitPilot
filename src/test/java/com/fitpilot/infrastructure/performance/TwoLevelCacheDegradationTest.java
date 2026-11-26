package com.fitpilot.infrastructure.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TwoLevelCacheDegradationTest {
    @Test
    void failedSharedReadSkipsTheRebuildLockAndKeepsInvalidationActive() {
        var redis = mock(StringRedisTemplate.class);
        var locks = mock(DistributedLockService.class);
        var properties = new PerformanceProperties(
                new PerformanceProperties.Cache(true, 100, 10, 30, 0, 5), null, null, null);
        var cache = new TwoLevelCache(new PerformanceConfig().hotDataLocalCache(properties), redis,
                new ObjectMapper(), locks, properties);
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("fixture outage"));

        assertThat(cache.get("exercise", "1", String.class, () -> Optional.of("from database")))
                .contains("from database");
        assertThat(cache.get("exercise", "1", String.class, () -> Optional.of("unexpected reload")))
                .contains("from database");

        verify(redis).opsForValue();
        verifyNoInteractions(locks);
        cache.evict("exercise", "1");
        verify(redis).delete("fitpilot:v1:cache:exercise:1");
    }
}
