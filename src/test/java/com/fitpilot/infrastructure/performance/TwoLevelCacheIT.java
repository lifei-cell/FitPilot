package com.fitpilot.infrastructure.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/** Independent L1 caches share a real Redis L2, as separate application instances do. */
@Testcontainers(disabledWithoutDocker = true)
class TwoLevelCacheIT {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private LettuceConnectionFactory connection;
    private StringRedisTemplate redis;
    private final AtomicReference<String> database = new AtomicReference<>("before");
    private final String id = UUID.randomUUID().toString();

    @BeforeEach
    void connect() {
        connection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
    }

    @AfterEach
    void disconnect() {
        if (connection != null) connection.destroy();
    }

    @Test
    void otherInstanceRefreshesDespiteContinuousReadsAfterWriterEvicts() {
        TwoLevelCache writer = instance(redis);
        TwoLevelCache reader = instance(redis);
        assertThat(read(writer)).contains("before");
        assertThat(read(reader)).contains("before");

        database.set("after");
        writer.evict("user-profile", id);
        assertThat(read(writer)).contains("after");
        // Eviction is local to the writer; the reader is allowed to remain briefly stale.
        assertThat(read(reader)).contains("before");
        await().pollInterval(Duration.ofMillis(20)).atMost(Duration.ofSeconds(4))
                .untilAsserted(() -> assertThat(read(reader)).contains("after"));
    }

    @Test
    void failedL2DeletionStillConvergesThroughExpiryWithoutReadRenewal() {
        StringRedisTemplate failingDelete = spy(redis);
        TwoLevelCache writer = instance(failingDelete);
        TwoLevelCache reader = instance(redis);
        assertThat(read(writer)).contains("before");
        assertThat(read(reader)).contains("before");

        database.set("after");
        String key = "fitpilot:v1:cache:user-profile:" + id;
        doThrow(new IllegalStateException("injected delete failure")).when(failingDelete).delete(key);
        writer.evict("user-profile", id);
        assertThat(redis.opsForValue().get(key)).isEqualTo("\"before\"");
        assertThat(read(reader)).contains("before");

        // Repeated L1/L2 hits must not perpetually extend the old payload's lifetime.
        await().pollInterval(Duration.ofMillis(20)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(read(reader)).contains("after"));
        assertThat(redis.opsForValue().get(key)).isEqualTo("\"after\"");
    }

    private Optional<String> read(TwoLevelCache cache) {
        return cache.get("user-profile", id, String.class, () -> Optional.of(database.get()));
    }

    private TwoLevelCache instance(StringRedisTemplate template) {
        // Shortened test durations exercise the production cache factory without long sleeps.
        var properties = new PerformanceProperties(
                new PerformanceProperties.Cache(true, 100, 1, 2, 0, 1), null, null, null);
        return new TwoLevelCache(new PerformanceConfig().hotDataLocalCache(properties), template,
                new ObjectMapper(), new DistributedLockService(template), properties);
    }
}
