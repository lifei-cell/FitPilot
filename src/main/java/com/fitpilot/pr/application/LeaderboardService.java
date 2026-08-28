package com.fitpilot.pr.application;

import com.fitpilot.infrastructure.performance.PerformanceProperties;
import com.fitpilot.pr.domain.PersonalRecord;
import com.fitpilot.pr.dto.LeaderboardRow;
import com.fitpilot.pr.infrastructure.PersonalRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class LeaderboardService {
    private static final Logger log = LoggerFactory.getLogger(LeaderboardService.class);
    private final StringRedisTemplate redis;
    private final PersonalRecordMapper mapper;
    private final PerformanceProperties properties;

    public LeaderboardService(StringRedisTemplate redis, PersonalRecordMapper mapper,
                              PerformanceProperties properties) {
        this.redis = redis;
        this.mapper = mapper;
        this.properties = properties;
    }

    public List<Entry> top(long exerciseId, String recordType, int limit) {
        String type = normalize(recordType);
        String key = key(exerciseId, type);
        try {
            if (!Boolean.TRUE.equals(redis.hasKey(key + ":loaded"))) load(key, exerciseId, type);
            Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet()
                    .reverseRangeWithScores(key, 0, limit - 1L);
            if (tuples != null) return entries(tuples);
        } catch (RuntimeException ex) {
            log.warn("Leaderboard Redis read degraded; exerciseId={} reason={}", exerciseId,
                    ex.getClass().getSimpleName());
        }
        return database(exerciseId, type, limit);
    }

    public void update(PersonalRecord record) {
        String key = key(record.exerciseId, record.recordType);
        try {
            redis.opsForZSet().add(key, String.valueOf(record.userId), score(record).doubleValue());
            Duration ttl = Duration.ofSeconds(properties.leaderboard().ttlSeconds());
            redis.expire(key, ttl);
            redis.opsForValue().set(key + ":loaded", "1", ttl);
        } catch (RuntimeException ex) {
            log.warn("Leaderboard update degraded; exerciseId={} reason={}", record.exerciseId,
                    ex.getClass().getSimpleName());
        }
    }

    private void load(String key, long exerciseId, String recordType) {
        List<LeaderboardRow> rows = mapper.selectLeaderboard(exerciseId, recordType, 100);
        for (LeaderboardRow row : rows) redis.opsForZSet().add(key, String.valueOf(row.userId), row.score.doubleValue());
        Duration ttl = Duration.ofSeconds(properties.leaderboard().ttlSeconds());
        if (!rows.isEmpty()) redis.expire(key, ttl);
        redis.opsForValue().set(key + ":loaded", "1", ttl);
    }

    private List<Entry> database(long exerciseId, String recordType, int limit) {
        List<LeaderboardRow> rows = mapper.selectLeaderboard(exerciseId, recordType, limit);
        List<Entry> result = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) result.add(new Entry(i + 1L, rows.get(i).userId, rows.get(i).score));
        return result;
    }

    private List<Entry> entries(Set<ZSetOperations.TypedTuple<String>> tuples) {
        List<Entry> result = new ArrayList<>(tuples.size());
        long rank = 1;
        for (var tuple : tuples) {
            if (tuple.getValue() != null && tuple.getScore() != null) {
                result.add(new Entry(rank++, Long.parseLong(tuple.getValue()), BigDecimal.valueOf(tuple.getScore())));
            }
        }
        return result;
    }

    private BigDecimal score(PersonalRecord record) {
        return switch (record.recordType) {
            case "ESTIMATED_1RM" -> record.estimated1rm;
            case "MAX_VOLUME" -> record.weightKg.multiply(BigDecimal.valueOf(record.reps));
            default -> record.weightKg;
        };
    }

    private String normalize(String type) {
        return type == null || type.isBlank() ? "ESTIMATED_1RM" : type.trim().toUpperCase();
    }
    private String key(long exerciseId, String type) {
        return "fitpilot:v1:pr:leaderboard:" + exerciseId + ":" + type;
    }

    public record Entry(long rank, long userId, BigDecimal score) {}
}
