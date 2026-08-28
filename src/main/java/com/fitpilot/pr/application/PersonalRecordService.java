package com.fitpilot.pr.application;

import com.fitpilot.pr.domain.PersonalRecord;
import com.fitpilot.pr.domain.PersonalRecordCalculator;
import com.fitpilot.pr.repository.PersonalRecordRepository;
import com.fitpilot.workout.domain.Workout;
import com.fitpilot.workout.domain.WorkoutExercise;
import com.fitpilot.workout.domain.WorkoutSet;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PersonalRecordService {
    private final PersonalRecordRepository repository;
    private final PersonalRecordCalculator calculator = new PersonalRecordCalculator();

    public PersonalRecordService(PersonalRecordRepository repository) { this.repository = repository; }

    public int calculateAndPersist(Workout workout, List<WorkoutExercise> workoutExercises, List<WorkoutSet> sets) {
        Map<Long, WorkoutExercise> exerciseBySnapshot = workoutExercises.stream()
                .collect(Collectors.toMap(e -> e.id, Function.identity()));
        Set<Long> exerciseIds = workoutExercises.stream().map(e -> e.exerciseId).collect(Collectors.toSet());
        Map<String, BigDecimal> best = new HashMap<>();
        for (PersonalRecord record : repository.findCurrent(workout.userId, exerciseIds)) {
            best.put(key(record.exerciseId, record.recordType), score(record));
        }

        int created = 0;
        for (WorkoutSet set : sets) {
            WorkoutExercise snapshot = exerciseBySnapshot.get(set.workoutExerciseId);
            if (snapshot == null) continue;
            for (var candidate : calculator.candidates(set)) {
                String key = key(snapshot.exerciseId, candidate.type());
                BigDecimal previous = best.get(key);
                if (previous != null && candidate.score().compareTo(previous) <= 0) continue;
                PersonalRecord record = new PersonalRecord();
                record.userId = workout.userId;
                record.exerciseId = snapshot.exerciseId;
                record.recordType = candidate.type();
                record.weightKg = set.weightKg;
                record.reps = set.reps;
                record.estimated1rm = candidate.estimated1rm();
                record.workoutId = workout.id;
                record.workoutSetId = set.id;
                record.achievedAt = set.completedAt;
                record.createdAt = LocalDateTime.now();
                repository.insert(record);
                best.put(key, candidate.score());
                created++;
            }
        }
        return created;
    }

    private BigDecimal score(PersonalRecord record) {
        return switch (record.recordType) {
            case "ESTIMATED_1RM" -> record.estimated1rm;
            case "MAX_VOLUME" -> record.weightKg.multiply(BigDecimal.valueOf(record.reps));
            default -> record.weightKg;
        };
    }

    private String key(long exerciseId, String type) { return exerciseId + ":" + type; }
}
