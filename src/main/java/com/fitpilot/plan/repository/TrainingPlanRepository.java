package com.fitpilot.plan.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fitpilot.plan.domain.TrainingPlan;
import com.fitpilot.plan.domain.TrainingPlanDay;
import com.fitpilot.plan.domain.TrainingPlanExercise;
import com.fitpilot.plan.infrastructure.TrainingPlanDayMapper;
import com.fitpilot.plan.infrastructure.TrainingPlanExerciseMapper;
import com.fitpilot.plan.infrastructure.TrainingPlanMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class TrainingPlanRepository {
    private final TrainingPlanMapper plans;
    private final TrainingPlanDayMapper days;
    private final TrainingPlanExerciseMapper exercises;

    public TrainingPlanRepository(TrainingPlanMapper plans, TrainingPlanDayMapper days,
                                  TrainingPlanExerciseMapper exercises) {
        this.plans = plans;
        this.days = days;
        this.exercises = exercises;
    }

    public void insert(TrainingPlan plan) { plans.insert(plan); }
    public void insert(TrainingPlanDay day) { days.insert(day); }
    public void insert(TrainingPlanExercise exercise) { exercises.insert(exercise); }
    public void update(TrainingPlan plan) { plans.updateById(plan); }
    public Optional<TrainingPlan> findOwned(long userId, long id) {
        return Optional.ofNullable(plans.selectOne(new QueryWrapper<TrainingPlan>().eq("id", id).eq("user_id", userId)));
    }
    public Optional<TrainingPlanDay> findOwnedDay(long userId, long planId, long dayId) {
        TrainingPlan plan = findOwned(userId, planId).orElse(null);
        if (plan == null) return Optional.empty();
        return Optional.ofNullable(days.selectOne(new QueryWrapper<TrainingPlanDay>().eq("id", dayId).eq("training_plan_id", planId)));
    }
    public List<TrainingPlanDay> findDays(long planId) {
        return days.selectList(new QueryWrapper<TrainingPlanDay>().eq("training_plan_id", planId).orderByAsc("day_number"));
    }
    public List<TrainingPlanExercise> findExercisesByDays(Collection<Long> dayIds) {
        if (dayIds.isEmpty()) return List.of();
        return exercises.selectList(new QueryWrapper<TrainingPlanExercise>().in("training_plan_day_id", dayIds)
                .orderByAsc("training_plan_day_id", "sequence"));
    }
    public List<TrainingPlanExercise> findDayExercises(long dayId) {
        return exercises.selectList(new QueryWrapper<TrainingPlanExercise>().eq("training_plan_day_id", dayId).orderByAsc("sequence"));
    }
    public Page<TrainingPlan> findPage(long userId, String status, long page, long size) {
        return plans.selectPage(Page.of(page, size), new QueryWrapper<TrainingPlan>().eq("user_id", userId)
                .eq(status != null, "status", status).orderByDesc("created_at"));
    }
    public void archiveActive(long userId, LocalDateTime now) {
        plans.update(null, new UpdateWrapper<TrainingPlan>().eq("user_id", userId).eq("status", "ACTIVE")
                .set("status", "ARCHIVED").set("ended_at", now.toLocalDate()).set("updated_at", now));
    }
}
