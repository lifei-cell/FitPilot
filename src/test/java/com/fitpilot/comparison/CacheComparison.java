package com.fitpilot.comparison;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.infrastructure.performance.*;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Three paths over the same query/payload. Two objects model independent application L1 caches. */
final class CacheComparison {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final DistributedLockService locks;
    private final PerformanceProperties properties;

    CacheComparison(JdbcTemplate jdbc, StringRedisTemplate redis, ObjectMapper json,
                    DistributedLockService locks, PerformanceProperties properties) {
        this.jdbc = jdbc; this.redis = redis; this.json = json; this.locks = locks; this.properties = properties;
    }

    void run() throws Exception {
        jdbc.execute("CREATE TABLE comparison_cache(id INT PRIMARY KEY, value TEXT NOT NULL)");
        jdbc.execute("INSERT INTO comparison_cache SELECT i, repeat('payload-',32) FROM generate_series(1,1000) i");
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> modes = List.of("PG_DIRECT", "REDIS_ONLY", "L1_L2");
        for (int round = 0; round < 3; round++) {
            for (String workload : List.of("hot", "cold")) {
                for (int offset = 0; offset < modes.size(); offset++) {
                    String mode = modes.get((round + offset) % modes.size());
                    String namespace = "comparison-" + round + "-" + workload + "-" + mode;
                    var nodes = List.of(cache(mode), cache(mode));
                    AtomicInteger loads = new AtomicInteger();
                    if (workload.equals("hot")) {
                        for (int id = 1; id <= 20; id++) for (var node : nodes) read(node, namespace, id, loads);
                    } else {
                        // Warm code paths without warming any measured key.
                        for (var node : nodes) read(node, namespace, 1000, loads);
                    }
                    loads.set(0);
                    long hitsBefore = nodes.stream().mapToLong(n -> n.stats().hits()).sum();
                    List<Double> samples = new ArrayList<>();
                    Random random = new Random(917);
                    for (int i = 0; i < 600; i++) {
                        int id = workload.equals("hot") ? random.nextInt(20) + 1 : i + 1;
                        long start = System.nanoTime();
                        assertThat(read(nodes.get(i % 2), namespace, id, loads).orElseThrow()).startsWith("payload-");
                        samples.add(ComparisonEvidence.elapsed(start));
                    }
                    var row = new LinkedHashMap<String, Object>(ComparisonEvidence.timings(samples));
                    row.put("round", round + 1); row.put("mode", mode); row.put("workload", workload);
                    row.put("dbLoads", loads.get());
                    row.put("l1Hits", nodes.stream().mapToLong(n -> n.stats().hits()).sum() - hitsBefore);
                    rows.add(row);
                }
            }
        }
        // Existing short-TTL trade-off: local invalidation cannot invalidate another process's L1.
        var a = cache("L1_L2"); var b = cache("L1_L2"); var count = new AtomicInteger();
        read(a, "consistency", 1, count); read(b, "consistency", 1, count);
        jdbc.update("UPDATE comparison_cache SET value='updated' WHERE id=1");
        a.evictAfterCommit("consistency", "1");
        long changed = System.nanoTime();
        assertThat(read(b, "consistency", 1, count).orElseThrow()).startsWith("payload-");
        await().atMost(Duration.ofSeconds(properties.cache().l1TtlSeconds() + 3)).until(() ->
                read(b, "consistency", 1, count).orElseThrow().equals("updated"));
        ComparisonEvidence.write("cache", Map.of("rows", rows, "remoteL1ConvergenceMs", ComparisonEvidence.elapsed(changed),
                "config", properties.cache(), "topology", "two cache objects, one JVM, local Docker PG/Redis",
                "scope", "sequential service calls; hot=20 keys; cold=600 distinct keys; 1000 rows; no HTTP or capacity claim"));
    }

    private Optional<String> read(TwoLevelCache cache, String namespace, int id, AtomicInteger loads) {
        return cache.get(namespace, String.valueOf(id), String.class, () -> {
            loads.incrementAndGet();
            return Optional.ofNullable(jdbc.queryForObject("SELECT value FROM comparison_cache WHERE id=?", String.class, id));
        });
    }

    private TwoLevelCache cache(String mode) {
        var config = properties.cache();
        var selected = new PerformanceProperties(new PerformanceProperties.Cache(!mode.equals("PG_DIRECT"),
                config.l1MaximumSize(), config.l1TtlSeconds(), config.l2TtlSeconds(), config.l2TtlJitterSeconds(),
                config.rebuildLockSeconds()), properties.rateLimit(), properties.idempotency(), properties.leaderboard());
        var local = Caffeine.newBuilder().maximumSize(config.l1MaximumSize()).recordStats()
                .expireAfterWrite(mode.equals("REDIS_ONLY") ? Duration.ZERO : Duration.ofSeconds(config.l1TtlSeconds()))
                .<String, String>build();
        return new TwoLevelCache(local, redis, json, locks, selected);
    }
}
