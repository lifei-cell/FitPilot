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
            redis.delete(List.of(key, key + ":loaded"));
        } catch (RuntimeException ex) {
            log.warn("Leaderboard update degraded; exerciseId={} reason={}", record.exerciseId,
                    ex.getClass().getSimpleName());
        }
    }

    private void load(String key, long exerciseId, String recordType) {
        List<LeaderboardRow> rows = mapper.selectLeaderboard(exerciseId, recordType, 100);
        for (LeaderboardRow row : rows) redis.opsForZSet().add(key, member(row.userId, row.username), row.score.doubleValue());
        Duration ttl = Duration.ofSeconds(properties.leaderboard().ttlSeconds());
        if (!rows.isEmpty()) redis.expire(key, ttl);
        redis.opsForValue().set(key + ":loaded", "1", ttl);
    }

    private List<Entry> database(long exerciseId, String recordType, int limit) {
        List<LeaderboardRow> rows = mapper.selectLeaderboard(exerciseId, recordType, limit);
        List<Entry> result = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            LeaderboardRow row = rows.get(i);
            result.add(new Entry(i + 1L, row.userId, row.username, row.score));
        }
        return result;
    }

    private List<Entry> entries(Set<ZSetOperations.TypedTuple<String>> tuples) {
        List<Entry> result = new ArrayList<>(tuples.size());
        long rank = 1;
        for (var tuple : tuples) {
            if (tuple.getValue() != null && tuple.getScore() != null) {
                String[] member = tuple.getValue().split("\\|", 2);
                if (member.length == 2) {
                    result.add(new Entry(rank++, Long.parseLong(member[0]), member[1],
                            BigDecimal.valueOf(tuple.getScore())));
                }
            }
        }
        return result;
    }

    private String normalize(String type) {
        return type == null || type.isBlank() ? "ESTIMATED_1RM" : type.trim().toUpperCase();
    }
    private String key(long exerciseId, String type) {
        return "fitpilot:v2:pr:leaderboard:" + exerciseId + ":" + type;
    }

    private String member(long userId, String username) { return userId + "|" + username; }

    public record Entry(long rank, long userId, String username, BigDecimal score) {}
}
