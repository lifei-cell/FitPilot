package com.fitpilot.workout.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fitpilot.workout.domain.Workout;
import com.fitpilot.workout.domain.WorkoutExercise;
import com.fitpilot.workout.domain.WorkoutSet;
import com.fitpilot.workout.infrastructure.WorkoutExerciseMapper;
import com.fitpilot.workout.infrastructure.WorkoutMapper;
import com.fitpilot.workout.infrastructure.WorkoutSetMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class WorkoutRepository {
    private final WorkoutMapper workouts;
    private final WorkoutExerciseMapper exercises;
    private final WorkoutSetMapper sets;

    public WorkoutRepository(WorkoutMapper workouts, WorkoutExerciseMapper exercises, WorkoutSetMapper sets) {
        this.workouts = workouts;
        this.exercises = exercises;
        this.sets = sets;
    }

    public void insert(Workout workout) { workouts.insert(workout); }
    public void insert(WorkoutExercise exercise) { exercises.insert(exercise); }
    public void insert(WorkoutSet set) { sets.insert(set); }
    public void update(Workout workout) { workouts.updateById(workout); }
    public void update(WorkoutSet set) { sets.updateById(set); }
    public void deleteSet(long id) { sets.deleteById(id); }
    public Optional<Workout> findOwned(long userId, long id) {
        return Optional.ofNullable(workouts.selectOne(new QueryWrapper<Workout>().eq("id", id).eq("user_id", userId)));
    }
    public Optional<WorkoutExercise> findExercise(long workoutId, long exerciseId) {
        return Optional.ofNullable(exercises.selectOne(new QueryWrapper<WorkoutExercise>()
                .eq("id", exerciseId).eq("workout_id", workoutId)));
    }
    public Optional<WorkoutSet> findSet(long workoutExerciseId, long setId) {
        return Optional.ofNullable(sets.selectOne(new QueryWrapper<WorkoutSet>()
                .eq("id", setId).eq("workout_exercise_id", workoutExerciseId)));
    }
    public Optional<WorkoutSet> findSet(long setId) { return Optional.ofNullable(sets.selectById(setId)); }
    public List<WorkoutExercise> findExercises(long workoutId) {
        return exercises.selectList(new QueryWrapper<WorkoutExercise>().eq("workout_id", workoutId).orderByAsc("sequence"));
    }
    public List<WorkoutSet> findSets(Collection<Long> workoutExerciseIds) {
        if (workoutExerciseIds.isEmpty()) return List.of();
        return sets.selectList(new QueryWrapper<WorkoutSet>().in("workout_exercise_id", workoutExerciseIds)
                .orderByAsc("workout_exercise_id", "set_number"));
    }
    public List<WorkoutSet> findSets(long workoutExerciseId) {
        return sets.selectList(new QueryWrapper<WorkoutSet>().eq("workout_exercise_id", workoutExerciseId).orderByAsc("set_number"));
    }
    public int nextExerciseSequence(long workoutId) {
        WorkoutExercise last = exercises.selectOne(new QueryWrapper<WorkoutExercise>().eq("workout_id", workoutId)
                .orderByDesc("sequence").last("LIMIT 1"));
        return last == null ? 1 : last.sequence + 1;
    }
    public int nextSetNumberForUpdate(long workoutExerciseId) {
        exercises.lockById(workoutExerciseId);
        WorkoutSet last = sets.selectOne(new QueryWrapper<WorkoutSet>().eq("workout_exercise_id", workoutExerciseId)
                .orderByDesc("set_number").last("LIMIT 1"));
        return last == null ? 1 : last.setNumber + 1;
    }
    public Page<Workout> findPage(long userId, LocalDateTime start, LocalDateTime end, String status, long page, long size) {
        return workouts.selectPage(Page.of(page, size), new QueryWrapper<Workout>().eq("user_id", userId)
                .ge(start != null, "started_at", start).le(end != null, "started_at", end)
                .eq(status != null, "status", status).orderByDesc("started_at"));
    }
}
