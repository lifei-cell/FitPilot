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
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class WorkoutRepository {
    private final WorkoutMapper workouts;
    private final WorkoutExerciseMapper exercises;
    private final WorkoutSetMapper sets;
    private final JdbcTemplate jdbc;

    public WorkoutRepository(WorkoutMapper workouts, WorkoutExerciseMapper exercises, WorkoutSetMapper sets,
                             JdbcTemplate jdbc) {
        this.workouts = workouts;
        this.exercises = exercises;
        this.sets = sets;
        this.jdbc = jdbc;
    }

    /** Serializes the rare start-workout command per user when Redis is unavailable. */
    public void lockCreationForUser(long userId) {
        jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, userId);
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
    /** All existing-workout writes lock the parent before checking state or locking children. */
    public Optional<Workout> lockOwned(long userId, long id) {
        return Optional.ofNullable(workouts.selectOne(new QueryWrapper<Workout>()
                .eq("id", id).eq("user_id", userId).last("FOR UPDATE")));
    }
    public Optional<Workout> findById(long id) { return Optional.ofNullable(workouts.selectById(id)); }
    public Optional<Workout> findByIdempotencyKey(long userId, String idempotencyKey) {
        return Optional.ofNullable(workouts.selectOne(new QueryWrapper<Workout>()
                .eq("user_id", userId).eq("idempotency_key", idempotencyKey).last("LIMIT 1")));
    }
    public Optional<Workout> findInProgress(long userId) {
        return Optional.ofNullable(workouts.selectOne(new QueryWrapper<Workout>()
                .eq("user_id", userId).eq("status", "IN_PROGRESS").last("LIMIT 1")));
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
        List<WorkoutSet> result = sets.selectList(new QueryWrapper<WorkoutSet>().in("workout_exercise_id", workoutExerciseIds)
                .orderByAsc("workout_exercise_id", "set_number"));
        // Match uncertain local submissions to server facts after a lost acknowledgement.
        String placeholders = String.join(",", java.util.Collections.nCopies(workoutExerciseIds.size(), "?"));
        java.util.Map<Long, String> operations = new java.util.HashMap<>();
        jdbc.query("SELECT (response_body->>'id')::bigint, operation_id::text FROM workout_set_receipt "
                        + "WHERE workout_exercise_id IN (" + placeholders + ")",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> operations.put(rs.getLong(1), rs.getString(2)),
                workoutExerciseIds.toArray());
        result.forEach(set -> set.clientOperationId = operations.get(set.id));
        return result;
    }
    public List<WorkoutSet> findSets(long workoutExerciseId) {
        return sets.selectList(new QueryWrapper<WorkoutSet>().eq("workout_exercise_id", workoutExerciseId).orderByAsc("set_number"));
    }
    /** Latest completed occurrence with usable working sets; never borrow another user's data. */
    public List<WorkoutSet> findPreviousWorkingSets(long userId, long exerciseId, LocalDateTime before) {
        List<Long> ids = jdbc.queryForList("""
                SELECT e.id FROM workout_exercise e JOIN workout w ON w.id=e.workout_id
                WHERE w.user_id=? AND e.exercise_id=? AND w.status='COMPLETED'
                  AND w.completed_at < ? AND EXISTS (
                    SELECT 1 FROM workout_set s WHERE s.workout_exercise_id=e.id
                    AND s.completed_at IS NOT NULL AND NOT s.is_warmup
                    AND s.weight_kg >= 0 AND s.reps > 0)
                ORDER BY w.completed_at DESC, w.id DESC, e.sequence DESC LIMIT 1
                """, Long.class, userId, exerciseId, before);
        if (ids.isEmpty()) return List.of();
        return findSets(ids.getFirst()).stream().filter(s -> s.completedAt != null
                && !Boolean.TRUE.equals(s.isWarmup) && s.weightKg != null
                && s.weightKg.signum() >= 0 && s.reps != null && s.reps > 0).toList();
    }
    public int nextExerciseSequence(long workoutId) {
        WorkoutExercise last = exercises.selectOne(new QueryWrapper<WorkoutExercise>().eq("workout_id", workoutId)
                .orderByDesc("sequence").last("LIMIT 1"));
        return last == null ? 1 : last.sequence + 1;
    }
    /**
     * The caller already holds the parent workout FOR UPDATE lock. That lock
     * serializes every set append in the workout, so taking the child lock
     * again only adds a round trip without increasing correctness.
     */
    public int nextSetNumber(long workoutExerciseId) {
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
