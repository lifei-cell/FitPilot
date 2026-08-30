package com.fitpilot.workout.application;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.common.response.PageResult;
import com.fitpilot.exercise.domain.Exercise;
import com.fitpilot.exercise.repository.ExerciseRepository;
import com.fitpilot.plan.domain.TrainingPlan;
import com.fitpilot.plan.domain.TrainingPlanDay;
import com.fitpilot.plan.domain.TrainingPlanExercise;
import com.fitpilot.plan.repository.TrainingPlanRepository;
import com.fitpilot.pr.repository.PersonalRecordRepository;
import com.fitpilot.infrastructure.events.EventOutboxService;
import com.fitpilot.infrastructure.events.EventPayloads;
import com.fitpilot.infrastructure.events.EventTypes;
import com.fitpilot.workout.domain.Workout;
import com.fitpilot.workout.domain.WorkoutExercise;
import com.fitpilot.workout.domain.WorkoutSet;
import com.fitpilot.workout.dto.WorkoutDtos;
import com.fitpilot.workout.repository.WorkoutRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class WorkoutService {
    private static final Logger log = LoggerFactory.getLogger(WorkoutService.class);
    private final WorkoutRepository repository;
    private final TrainingPlanRepository plans;
    private final ExerciseRepository exercises;
    private final PersonalRecordRepository personalRecordRepository;
    private final EventOutboxService events;

    public WorkoutService(WorkoutRepository repository, TrainingPlanRepository plans, ExerciseRepository exercises,
                          PersonalRecordRepository personalRecordRepository, EventOutboxService events) {
        this.repository = repository;
        this.plans = plans;
        this.exercises = exercises;
        this.personalRecordRepository = personalRecordRepository;
        this.events = events;
    }

    @Transactional
    public WorkoutDtos.WorkoutView create(long userId, WorkoutDtos.CreateRequest request) {
        if (repository.findInProgress(userId).isPresent()) {
            throw alreadyInProgress();
        }
        TrainingPlan plan = plans.findOwned(userId, request.trainingPlanId()).orElseThrow(this::planNotFound);
        if (!"ACTIVE".equals(plan.status)) {
            throw new BusinessException(ErrorCode.INVALID_TRAINING_PLAN, "only an active plan can start a workout");
        }
        TrainingPlanDay day = plans.findOwnedDay(userId, plan.id, request.trainingPlanDayId())
                .orElseThrow(this::planNotFound);
        List<TrainingPlanExercise> plannedExercises = plans.findDayExercises(day.id);
        if (plannedExercises.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_TRAINING_PLAN, "training day has no exercises");
        }
        Map<Long, Exercise> exerciseById = exercises.findActiveByIds(
                plannedExercises.stream().map(e -> e.exerciseId).toList()).stream()
                .collect(Collectors.toMap(e -> e.id, Function.identity()));
        if (exerciseById.size() != plannedExercises.stream().map(e -> e.exerciseId).distinct().count()) {
            throw new BusinessException(ErrorCode.EXERCISE_NOT_FOUND, "planned exercise is unavailable", HttpStatus.NOT_FOUND);
        }

        LocalDateTime now = LocalDateTime.now();
        Workout workout = new Workout();
        workout.userId = userId;
        workout.trainingPlanId = plan.id;
        workout.trainingPlanDayId = day.id;
        workout.name = request.name() == null || request.name().isBlank() ? day.name : request.name().trim();
        workout.status = "IN_PROGRESS";
        workout.startedAt = now;
        workout.notes = request.notes();
        workout.createdAt = now;
        workout.updatedAt = now;
        try {
            repository.insert(workout);
        } catch (DuplicateKeyException ex) {
            throw alreadyInProgress();
        }

        for (TrainingPlanExercise source : plannedExercises) {
            WorkoutExercise snapshot = new WorkoutExercise();
            snapshot.workoutId = workout.id;
            snapshot.exerciseId = source.exerciseId;
            snapshot.sequence = source.sequence;
            snapshot.exerciseName = exerciseById.get(source.exerciseId).name;
            snapshot.targetSets = source.targetSets;
            snapshot.targetRepsMin = source.targetRepsMin;
            snapshot.targetRepsMax = source.targetRepsMax;
            snapshot.targetRpe = source.targetRpe;
            snapshot.restSeconds = source.restSeconds;
            snapshot.notes = source.notes;
            snapshot.createdAt = now;
            repository.insert(snapshot);
        }
        return get(userId, workout.id);
    }

    public WorkoutDtos.WorkoutView get(long userId, long workoutId) {
        Workout workout = owned(userId, workoutId);
        List<WorkoutExercise> exerciseList = repository.findExercises(workoutId);
        Map<Long, List<WorkoutSet>> setsByExercise = repository.findSets(exerciseList.stream().map(e -> e.id).toList())
                .stream().collect(Collectors.groupingBy(s -> s.workoutExerciseId, LinkedHashMap::new, Collectors.toList()));
        return view(workout, exerciseList, setsByExercise);
    }

    public WorkoutDtos.WorkoutView active(long userId) {
        Workout workout = repository.findInProgress(userId).orElseThrow(this::workoutNotFound);
        return get(userId, workout.id);
    }

    public PageResult<WorkoutDtos.WorkoutSummary> list(long userId, LocalDateTime start, LocalDateTime end,
                                                        String status, long page, long size) {
        if (start != null && end != null && start.isAfter(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "startDate must not be after endDate");
        }
        String normalizedStatus = status == null || status.isBlank() ? null : status.toUpperCase();
        Page<Workout> result = repository.findPage(userId, start, end, normalizedStatus, page, size);
        var items = result.getRecords().stream().map(w -> new WorkoutDtos.WorkoutSummary(
                w.id, w.name, w.status, w.startedAt, w.completedAt, w.durationSeconds)).toList();
        return PageResult.of(items, result.getTotal(), page, size);
    }

    @Transactional
    public WorkoutDtos.ExerciseView addExercise(long userId, long workoutId, WorkoutDtos.AddExerciseRequest request) {
        ensureInProgress(owned(userId, workoutId));
        Exercise source = exercises.findActive(request.exerciseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EXERCISE_NOT_FOUND, "exercise not found", HttpStatus.NOT_FOUND));
        WorkoutExercise snapshot = new WorkoutExercise();
        snapshot.workoutId = workoutId;
        snapshot.exerciseId = source.id;
        snapshot.exerciseName = source.name;
        snapshot.sequence = repository.nextExerciseSequence(workoutId);
        snapshot.notes = request.notes();
        snapshot.createdAt = LocalDateTime.now();
        repository.insert(snapshot);
        return exerciseView(snapshot, List.of());
    }

    @Transactional
    public WorkoutDtos.SetView addSet(long userId, long workoutId, long workoutExerciseId, WorkoutDtos.SetRequest request) {
        ensureInProgress(owned(userId, workoutId));
        repository.findExercise(workoutId, workoutExerciseId).orElseThrow(this::workoutNotFound);
        LocalDateTime now = LocalDateTime.now();
        WorkoutSet set = new WorkoutSet();
        set.workoutExerciseId = workoutExerciseId;
        set.setNumber = repository.nextSetNumberForUpdate(workoutExerciseId);
        apply(set, request);
        set.completedAt = now;
        set.createdAt = now;
        set.updatedAt = now;
        repository.insert(set);
        return setView(set);
    }

    @Transactional
    public WorkoutDtos.SetView updateSet(long userId, long workoutId, long setId, WorkoutDtos.SetRequest request) {
        ensureInProgress(owned(userId, workoutId));
        WorkoutSet set = ownedSet(workoutId, setId);
        apply(set, request);
        set.updatedAt = LocalDateTime.now();
        repository.update(set);
        return setView(set);
    }

    @Transactional
    public void deleteSet(long userId, long workoutId, long setId) {
        ensureInProgress(owned(userId, workoutId));
        WorkoutSet set = ownedSet(workoutId, setId);
        repository.deleteSet(set.id);
    }

    @Transactional
    public WorkoutDtos.WorkoutView cancel(long userId, long workoutId) {
        Workout workout = owned(userId, workoutId);
        if ("COMPLETED".equals(workout.status)) {
            throw new BusinessException(ErrorCode.WORKOUT_ALREADY_COMPLETED, "completed workout cannot be cancelled", HttpStatus.CONFLICT);
        }
        if (!"CANCELLED".equals(workout.status)) {
            workout.status = "CANCELLED";
            workout.updatedAt = LocalDateTime.now();
            repository.update(workout);
        }
        return get(userId, workoutId);
    }

    @Transactional
    public WorkoutDtos.CompleteView complete(long userId, long workoutId) {
        Workout workout = owned(userId, workoutId);
        if ("COMPLETED".equals(workout.status)) {
            return new WorkoutDtos.CompleteView(get(userId, workoutId),
                    personalRecordRepository.countByWorkout(userId, workoutId));
        }
        ensureInProgress(workout);
        List<WorkoutExercise> exerciseList = repository.findExercises(workoutId);
        List<WorkoutSet> setList = repository.findSets(exerciseList.stream().map(e -> e.id).toList());
        if (setList.stream().noneMatch(this::validCompletedSet)) {
            throw new BusinessException(ErrorCode.INVALID_WORKOUT_SET, "workout requires at least one valid completed set");
        }
        LocalDateTime completedAt = LocalDateTime.now();
        workout.completedAt = completedAt;
        workout.durationSeconds = Math.toIntExact(ChronoUnit.SECONDS.between(workout.startedAt, completedAt));
        workout.status = "COMPLETED";
        workout.updatedAt = completedAt;
        repository.update(workout);
        events.append("Workout", workout.id, EventTypes.WORKOUT_COMPLETED,
                new EventPayloads.WorkoutCompleted(workout.id, userId, completedAt,
                        workout.durationSeconds, (int) setList.stream().filter(this::validCompletedSet).count()));
        log.info("operation=CompleteWorkout userId={} resourceId={} sets={} durationSeconds={} event=accepted",
                userId, workoutId, setList.size(), workout.durationSeconds);
        return new WorkoutDtos.CompleteView(get(userId, workoutId), 0);
    }

    private WorkoutSet ownedSet(long workoutId, long setId) {
        WorkoutSet set = repository.findSet(setId).orElseThrow(this::workoutNotFound);
        repository.findExercise(workoutId, set.workoutExerciseId).orElseThrow(this::workoutNotFound);
        return set;
    }

    private Workout owned(long userId, long workoutId) {
        return repository.findOwned(userId, workoutId).orElseThrow(this::workoutNotFound);
    }

    private void ensureInProgress(Workout workout) {
        if ("COMPLETED".equals(workout.status))
            throw new BusinessException(ErrorCode.WORKOUT_ALREADY_COMPLETED, "workout is already completed", HttpStatus.CONFLICT);
        if ("CANCELLED".equals(workout.status))
            throw new BusinessException(ErrorCode.WORKOUT_CANCELLED, "workout is cancelled", HttpStatus.CONFLICT);
    }

    private void apply(WorkoutSet set, WorkoutDtos.SetRequest request) {
        set.weightKg = request.weightKg();
        set.reps = request.reps();
        set.rpe = request.rpe();
        set.rir = request.rir();
        set.isWarmup = Boolean.TRUE.equals(request.isWarmup());
        set.isFailure = Boolean.TRUE.equals(request.isFailure());
    }

    private boolean validCompletedSet(WorkoutSet set) { return set.completedAt != null && set.reps != null && set.reps > 0; }

    private WorkoutDtos.WorkoutView view(Workout w, List<WorkoutExercise> exerciseList,
                                          Map<Long, List<WorkoutSet>> setsByExercise) {
        return new WorkoutDtos.WorkoutView(w.id, w.trainingPlanId, w.trainingPlanDayId, w.name, w.status,
                w.startedAt, w.completedAt, w.durationSeconds, w.notes,
                exerciseList.stream().map(e -> exerciseView(e, setsByExercise.getOrDefault(e.id, List.of()))).toList());
    }

    private WorkoutDtos.ExerciseView exerciseView(WorkoutExercise e, List<WorkoutSet> sets) {
        return new WorkoutDtos.ExerciseView(e.id, e.exerciseId, e.exerciseName, e.sequence, e.targetSets,
                e.targetRepsMin, e.targetRepsMax, e.targetRpe, e.restSeconds, e.notes,
                sets.stream().map(this::setView).toList());
    }

    private WorkoutDtos.SetView setView(WorkoutSet s) {
        return new WorkoutDtos.SetView(s.id, s.setNumber, s.weightKg, s.reps, s.rpe, s.rir,
                Boolean.TRUE.equals(s.isWarmup), Boolean.TRUE.equals(s.isFailure), s.completedAt);
    }

    private BusinessException workoutNotFound() {
        return new BusinessException(ErrorCode.WORKOUT_NOT_FOUND, "workout resource not found", HttpStatus.NOT_FOUND);
    }
    private BusinessException planNotFound() {
        return new BusinessException(ErrorCode.TRAINING_PLAN_NOT_FOUND, "training plan resource not found", HttpStatus.NOT_FOUND);
    }
    private BusinessException alreadyInProgress() {
        return new BusinessException(ErrorCode.WORKOUT_ALREADY_IN_PROGRESS,
                "finish or cancel the current workout before starting another", HttpStatus.CONFLICT);
    }
}
