package com.fitpilot.pr.application;

import com.fitpilot.pr.domain.PersonalRecord;
import com.fitpilot.pr.domain.PersonalRecordCalculator;
import com.fitpilot.pr.repository.PersonalRecordRepository;
import com.fitpilot.workout.domain.Workout;
import com.fitpilot.workout.domain.WorkoutExercise;
import com.fitpilot.workout.domain.WorkoutSet;
import com.fitpilot.infrastructure.events.EventOutboxService;
import com.fitpilot.infrastructure.events.EventPayloads;
import com.fitpilot.infrastructure.events.EventTypes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PersonalRecordService {
    private final PersonalRecordRepository repository;
    private final LeaderboardService leaderboard;
    private final PersonalRecordCalculator calculator = new PersonalRecordCalculator();
    private final EventOutboxService events;

    public PersonalRecordService(PersonalRecordRepository repository, LeaderboardService leaderboard,
                                 EventOutboxService events) {
        this.repository = repository;
        this.leaderboard = leaderboard;
        this.events = events;
    }

    public int calculateAndPersist(Workout workout, List<WorkoutExercise> workoutExercises, List<WorkoutSet> sets) {
        Map<Long, WorkoutExercise> exerciseBySnapshot = workoutExercises.stream()
                .collect(Collectors.toMap(e -> e.id, Function.identity()));
        Set<Long> exerciseIds = workoutExercises.stream().map(e -> e.exerciseId).collect(Collectors.toSet());
        Map<String, BigDecimal> best = new HashMap<>();
        for (PersonalRecord record : repository.findCurrent(workout.userId, exerciseIds)) {
            best.put(key(record.exerciseId, record.recordType), score(record));
        }

        int created = 0;
        List<PersonalRecord> newRecords = new ArrayList<>();
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
                if (!repository.insertIfAbsent(record)) continue;
                events.append("PersonalRecord", record.id, EventTypes.PERSONAL_RECORD_CREATED,
                        new EventPayloads.PersonalRecordCreated(record.id, workout.userId, snapshot.exerciseId,
                                snapshot.exerciseName, record.recordType, candidate.score(), workout.id, record.achievedAt));
                newRecords.add(record);
                best.put(key, candidate.score());
                created++;
            }
        }
        updateLeaderboardAfterCommit(newRecords);
        return created;
    }

    private void updateLeaderboardAfterCommit(List<PersonalRecord> records) {
        if (records.isEmpty()) return;
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { records.forEach(leaderboard::update); }
            });
        } else records.forEach(leaderboard::update);
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
