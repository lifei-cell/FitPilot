package com.fitpilot.plan.application;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.common.response.PageResult;
import com.fitpilot.exercise.repository.ExerciseRepository;
import com.fitpilot.infrastructure.performance.TwoLevelCache;
import com.fitpilot.infrastructure.events.EventOutboxService;
import com.fitpilot.infrastructure.events.EventPayloads;
import com.fitpilot.infrastructure.events.EventTypes;
import com.fitpilot.plan.domain.TrainingPlan;
import com.fitpilot.plan.domain.TrainingPlanDay;
import com.fitpilot.plan.domain.TrainingPlanExercise;
import com.fitpilot.plan.domain.TrainingPlanValidator;
import com.fitpilot.plan.dto.TrainingPlanDtos;
import com.fitpilot.plan.repository.TrainingPlanRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TrainingPlanService {
    private final TrainingPlanRepository repository;
    private final ExerciseRepository exercises;
    private final TwoLevelCache cache;
    private final EventOutboxService events;

    public TrainingPlanService(TrainingPlanRepository repository, ExerciseRepository exercises, TwoLevelCache cache,
                               EventOutboxService events) {
        this.repository = repository;
        this.exercises = exercises;
        this.cache = cache;
        this.events = events;
    }

    @Transactional
    public TrainingPlanDtos.PlanView create(long userId, TrainingPlanDtos.CreateRequest request) {
        TrainingPlanValidator.validate(request);
        Set<Long> requestedIds = request.days().stream().flatMap(day -> day.exercises().stream())
                .map(TrainingPlanDtos.ExerciseRequest::exerciseId).collect(Collectors.toSet());
        if (exercises.findActiveByIds(requestedIds).size() != requestedIds.size()) {
            throw new BusinessException(ErrorCode.EXERCISE_NOT_FOUND, "one or more exercises do not exist", HttpStatus.NOT_FOUND);
        }

        LocalDateTime now = LocalDateTime.now();
        TrainingPlan plan = new TrainingPlan();
        plan.userId = userId;
        plan.name = request.name();
        plan.description = request.description();
        plan.goal = request.goal();
        plan.durationWeeks = request.durationWeeks();
        plan.daysPerWeek = request.days().size();
        plan.status = "DRAFT";
        plan.version = 1;
        plan.createdAt = now;
        plan.updatedAt = now;
        repository.insert(plan);

        for (var dayRequest : request.days()) {
            TrainingPlanDay day = new TrainingPlanDay();
            day.trainingPlanId = plan.id;
            day.dayNumber = dayRequest.dayNumber();
            day.name = dayRequest.name();
            day.notes = dayRequest.notes();
            day.createdAt = now;
            repository.insert(day);
            for (var exerciseRequest : dayRequest.exercises()) {
                TrainingPlanExercise exercise = new TrainingPlanExercise();
                exercise.trainingPlanDayId = day.id;
                exercise.exerciseId = exerciseRequest.exerciseId();
                exercise.sequence = exerciseRequest.sequence();
                exercise.targetSets = exerciseRequest.targetSets();
                exercise.targetRepsMin = exerciseRequest.targetRepsMin();
                exercise.targetRepsMax = exerciseRequest.targetRepsMax();
                exercise.targetRpe = exerciseRequest.targetRpe();
                exercise.restSeconds = exerciseRequest.restSeconds();
                exercise.notes = exerciseRequest.notes();
                exercise.createdAt = now;
                repository.insert(exercise);
            }
        }
        events.append("TrainingPlan", plan.id, EventTypes.TRAINING_PLAN_CREATED,
                new EventPayloads.TrainingPlanChanged(plan.id, userId, plan.status, plan.version, now));
        return get(userId, plan.id);
    }

    public TrainingPlanDtos.PlanView get(long userId, long planId) {
        TrainingPlan plan = repository.findOwned(userId, planId).orElseThrow(this::notFound);
        List<TrainingPlanDay> days = repository.findDays(planId);
        List<TrainingPlanExercise> planExercises = repository.findExercisesByDays(days.stream().map(d -> d.id).toList());
        Map<Long, String> exerciseNames = exercises.findActiveByIds(planExercises.stream().map(e -> e.exerciseId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(e -> e.id, e -> e.name));
        Map<Long, List<TrainingPlanExercise>> byDay = planExercises
                .stream().collect(Collectors.groupingBy(e -> e.trainingPlanDayId, LinkedHashMap::new, Collectors.toList()));
        List<TrainingPlanDtos.DayView> dayViews = days.stream().map(day -> new TrainingPlanDtos.DayView(
                day.id, day.dayNumber, day.name, day.notes,
                byDay.getOrDefault(day.id, List.of()).stream().map(e -> exerciseView(e, exerciseNames.get(e.exerciseId))).toList())).toList();
        return planView(plan, dayViews);
    }

    @Transactional
    public TrainingPlanDtos.PlanView update(long userId, long planId, TrainingPlanDtos.UpdateRequest request) {
        TrainingPlanValidator.validate(request);
        Set<Long> requestedIds = request.days().stream().flatMap(day -> day.exercises().stream())
                .map(TrainingPlanDtos.ExerciseRequest::exerciseId).collect(Collectors.toSet());
        if (exercises.findActiveByIds(requestedIds).size() != requestedIds.size()) {
            throw new BusinessException(ErrorCode.EXERCISE_NOT_FOUND, "one or more exercises do not exist", HttpStatus.NOT_FOUND);
        }
        TrainingPlan plan = repository.findOwned(userId, planId).orElseThrow(this::notFound);
        if (!"DRAFT".equals(plan.status)) {
            throw new BusinessException(ErrorCode.INVALID_TRAINING_PLAN, "only a draft plan can be edited", HttpStatus.CONFLICT);
        }
        LocalDateTime now = LocalDateTime.now();
        plan.name = request.name();
        plan.description = request.description();
        plan.goal = request.goal();
        plan.durationWeeks = request.durationWeeks();
        plan.daysPerWeek = request.days().size();
        plan.updatedAt = now;
        if (!repository.updateDraft(userId, plan, request.version())) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
                    "training plan has changed; reload before saving", HttpStatus.CONFLICT);
        }
        repository.deleteDays(planId);
        insertDays(planId, request.days(), now);
        events.append("TrainingPlan", plan.id, EventTypes.TRAINING_PLAN_UPDATED,
                new EventPayloads.TrainingPlanChanged(plan.id, userId, plan.status, request.version() + 1, now));
        return get(userId, planId);
    }

    public PageResult<TrainingPlanDtos.PlanSummary> list(long userId, String status, long page, long size) {
        String normalizedStatus = status == null || status.isBlank() ? null : status.toUpperCase();
        Page<TrainingPlan> result = repository.findPage(userId, normalizedStatus, page, size);
        List<TrainingPlanDtos.PlanSummary> items = result.getRecords().stream().map(p -> new TrainingPlanDtos.PlanSummary(
                p.id, p.name, p.goal, p.durationWeeks, p.daysPerWeek, p.status, p.startedAt)).toList();
        return PageResult.of(items, result.getTotal(), page, size);
    }

    public TrainingPlanDtos.PlanView active(long userId) {
        return cache.get("active-plan", String.valueOf(userId), TrainingPlanDtos.PlanView.class,
                        () -> repository.findActive(userId).map(plan -> get(userId, plan.id)))
                .orElseThrow(this::notFound);
    }

    @Transactional
    public TrainingPlanDtos.PlanView activate(long userId, long planId) {
        TrainingPlan plan = repository.findOwned(userId, planId).orElseThrow(this::notFound);
        if ("ACTIVE".equals(plan.status)) {
            throw new BusinessException(ErrorCode.TRAINING_PLAN_ALREADY_ACTIVE, "training plan is already active", HttpStatus.CONFLICT);
        }
        if ("COMPLETED".equals(plan.status)) {
            throw new BusinessException(ErrorCode.INVALID_TRAINING_PLAN, "completed plan cannot be activated");
        }
        LocalDateTime now = LocalDateTime.now();
        repository.archiveActive(userId, now);
        plan.status = "ACTIVE";
        plan.version = plan.version + 1;
        plan.startedAt = now.toLocalDate();
        plan.endedAt = null;
        plan.updatedAt = now;
        repository.update(plan);
        events.append("TrainingPlan", plan.id, EventTypes.TRAINING_PLAN_UPDATED,
                new EventPayloads.TrainingPlanChanged(plan.id, userId, plan.status, plan.version, now));
        cache.evictAfterCommit("active-plan", String.valueOf(userId));
        return get(userId, planId);
    }

    private TrainingPlanDtos.ExerciseView exerciseView(TrainingPlanExercise e, String exerciseName) {
        return new TrainingPlanDtos.ExerciseView(e.id, e.exerciseId, exerciseName, e.sequence, e.targetSets,
                e.targetRepsMin, e.targetRepsMax, e.targetRpe, e.restSeconds, e.notes);
    }

    private void insertDays(long planId, List<TrainingPlanDtos.DayRequest> requests, LocalDateTime now) {
        for (var dayRequest : requests) {
            TrainingPlanDay day = new TrainingPlanDay();
            day.trainingPlanId = planId;
            day.dayNumber = dayRequest.dayNumber();
            day.name = dayRequest.name();
            day.notes = dayRequest.notes();
            day.createdAt = now;
            repository.insert(day);
            for (var exerciseRequest : dayRequest.exercises()) {
                TrainingPlanExercise exercise = new TrainingPlanExercise();
                exercise.trainingPlanDayId = day.id;
                exercise.exerciseId = exerciseRequest.exerciseId();
                exercise.sequence = exerciseRequest.sequence();
                exercise.targetSets = exerciseRequest.targetSets();
                exercise.targetRepsMin = exerciseRequest.targetRepsMin();
                exercise.targetRepsMax = exerciseRequest.targetRepsMax();
                exercise.targetRpe = exerciseRequest.targetRpe();
                exercise.restSeconds = exerciseRequest.restSeconds();
                exercise.notes = exerciseRequest.notes();
                exercise.createdAt = now;
                repository.insert(exercise);
            }
        }
    }

    private TrainingPlanDtos.PlanView planView(TrainingPlan p, List<TrainingPlanDtos.DayView> days) {
        return new TrainingPlanDtos.PlanView(p.id, p.name, p.description, p.goal, p.durationWeeks,
                p.daysPerWeek, p.status, p.version, p.startedAt, p.endedAt, days);
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.TRAINING_PLAN_NOT_FOUND, "training plan not found", HttpStatus.NOT_FOUND);
    }
}
