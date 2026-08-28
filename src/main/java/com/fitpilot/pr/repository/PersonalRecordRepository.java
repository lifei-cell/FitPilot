package com.fitpilot.pr.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fitpilot.pr.domain.PersonalRecord;
import com.fitpilot.pr.infrastructure.PersonalRecordMapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public class PersonalRecordRepository {
    private final PersonalRecordMapper mapper;
    public PersonalRecordRepository(PersonalRecordMapper mapper) { this.mapper = mapper; }

    public void insert(PersonalRecord record) { mapper.insert(record); }
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
