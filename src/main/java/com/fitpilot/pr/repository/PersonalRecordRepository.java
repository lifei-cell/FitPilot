package com.fitpilot.pr.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fitpilot.pr.domain.PersonalRecord;
import com.fitpilot.pr.infrastructure.PersonalRecordMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public class PersonalRecordRepository {
    private final PersonalRecordMapper mapper;
    private final JdbcTemplate jdbc;
    public PersonalRecordRepository(PersonalRecordMapper mapper, JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    public void insert(PersonalRecord record) { mapper.insert(record); }
    public boolean insertIfAbsent(PersonalRecord record) {
        KeyHolder keys = new GeneratedKeyHolder();
        int changed = jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO personal_record(user_id, exercise_id, record_type, weight_kg, reps,
                        estimated_1rm, workout_id, workout_set_id, achieved_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (workout_set_id, record_type) DO NOTHING
                    """, new String[]{"id"});
            statement.setLong(1, record.userId);
            statement.setLong(2, record.exerciseId);
            statement.setString(3, record.recordType);
            statement.setBigDecimal(4, record.weightKg);
            if (record.reps == null) statement.setNull(5, java.sql.Types.INTEGER); else statement.setInt(5, record.reps);
            statement.setBigDecimal(6, record.estimated1rm);
            statement.setLong(7, record.workoutId);
            statement.setLong(8, record.workoutSetId);
            statement.setObject(9, record.achievedAt);
            statement.setObject(10, record.createdAt);
            return statement;
        }, keys);
        if (changed == 1 && keys.getKey() != null) record.id = keys.getKey().longValue();
        return changed == 1;
    }
    public List<PersonalRecord> findCurrent(long userId) { return mapper.selectCurrent(userId); }
    public List<PersonalRecord> findCurrent(long userId, Collection<Long> exerciseIds) {
        if (exerciseIds.isEmpty()) return List.of();
        return findCurrent(userId).stream().filter(record -> exerciseIds.contains(record.exerciseId)).toList();
    }
    public List<PersonalRecord> findCurrentForExercise(long userId, long exerciseId) {
        return findCurrent(userId).stream().filter(record -> record.exerciseId == exerciseId).toList();
    }
    public List<PersonalRecord> findHistory(long userId, long exerciseId) {
        return mapper.selectList(new QueryWrapper<PersonalRecord>().eq("user_id", userId)
                .eq("exercise_id", exerciseId).orderByDesc("achieved_at", "id"));
    }
    public int countByWorkout(long userId, long workoutId) {
        return Math.toIntExact(mapper.selectCount(new QueryWrapper<PersonalRecord>()
                .eq("user_id", userId).eq("workout_id", workoutId)));
    }
    public long countAchievedBetween(long userId, java.time.LocalDateTime start, java.time.LocalDateTime end) {
        return mapper.selectCount(new QueryWrapper<PersonalRecord>().eq("user_id", userId)
                .ge("achieved_at", start).lt("achieved_at", end));
    }
}
