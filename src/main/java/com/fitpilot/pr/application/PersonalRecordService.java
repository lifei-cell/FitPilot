package com.fitpilot.pr.application;

import com.fitpilot.pr.domain.PersonalRecord;
import com.fitpilot.pr.domain.PersonalRecordCalculator;
import com.fitpilot.pr.dto.PersonalRecordView;
import com.fitpilot.pr.repository.PersonalRecordRepository;
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

    public int calculateAndPersist(CompletedWorkout workout) {
        Map<Long, CompletedExercise> exerciseBySnapshot = workout.exercises().stream()
                .collect(Collectors.toMap(CompletedExercise::snapshotId, Function.identity()));
        Set<Long> exerciseIds = workout.exercises().stream()
                .map(CompletedExercise::exerciseId).collect(Collectors.toSet());
        Map<String, BigDecimal> best = new HashMap<>();
        for (PersonalRecord record : repository.findCurrent(workout.userId(), exerciseIds)) {
            best.put(key(record.exerciseId, record.recordType), score(record));
        }

        int created = 0;
        List<PersonalRecord> newRecords = new ArrayList<>();
        for (CompletedSet set : workout.sets()) {
            CompletedExercise snapshot = exerciseBySnapshot.get(set.workoutExerciseId());
            if (snapshot == null) continue;
            var performance = new PersonalRecordCalculator.SetPerformance(
                    set.weightKg(), set.reps(), set.warmup(), set.completedAt());
            for (var candidate : calculator.candidates(performance)) {
                String key = key(snapshot.exerciseId(), candidate.type());
                BigDecimal previous = best.get(key);
                if (previous != null && candidate.score().compareTo(previous) <= 0) continue;
                PersonalRecord record = new PersonalRecord();
                record.userId = workout.userId();
                record.exerciseId = snapshot.exerciseId();
                record.recordType = candidate.type();
                record.weightKg = set.weightKg();
                record.reps = set.reps();
                record.estimated1rm = candidate.estimated1rm();
                record.workoutId = workout.id();
                record.workoutSetId = set.id();
                record.achievedAt = set.completedAt();
                record.createdAt = LocalDateTime.now();
                if (!repository.insertIfAbsent(record)) continue;
                events.append("PersonalRecord", record.id, EventTypes.PERSONAL_RECORD_CREATED,
                        new EventPayloads.PersonalRecordCreated(record.id, workout.userId(), snapshot.exerciseId(),
                                snapshot.exerciseName(), record.recordType, candidate.score(), workout.id(), record.achievedAt));
                newRecords.add(record);
                best.put(key, candidate.score());
                created++;
            }
        }
        updateLeaderboardAfterCommit(newRecords);
        return created;
    }

    public List<PersonalRecordView> current(long userId) {
        return repository.findCurrent(userId).stream().map(PersonalRecordView::from).toList();
    }

    public List<PersonalRecordView> currentForExercise(long userId, long exerciseId) {
        return repository.findCurrentForExercise(userId, exerciseId).stream().map(PersonalRecordView::from).toList();
    }

    public List<PersonalRecordView> history(long userId, long exerciseId) {
        return repository.findHistory(userId, exerciseId).stream().map(PersonalRecordView::from).toList();
    }

    public int countByWorkout(long userId, long workoutId) {
        return repository.countByWorkout(userId, workoutId);
    }

    public long countAchievedBetween(long userId, LocalDateTime start, LocalDateTime end) {
        return repository.countAchievedBetween(userId, start, end);
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

    public record CompletedWorkout(long id, long userId, List<CompletedExercise> exercises,
                                   List<CompletedSet> sets) {}
    public record CompletedExercise(long snapshotId, long exerciseId, String exerciseName) {}
    public record CompletedSet(long id, long workoutExerciseId, BigDecimal weightKg, Integer reps,
                               boolean warmup, LocalDateTime completedAt) {}
}
