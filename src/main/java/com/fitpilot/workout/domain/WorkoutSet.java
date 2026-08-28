package com.fitpilot.workout.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("workout_set")
public class WorkoutSet {
    @TableId(type = IdType.AUTO) public Long id;
    public Long workoutExerciseId;
    public Integer setNumber;
    public BigDecimal weightKg;
    public Integer reps;
    public BigDecimal rpe;
    public BigDecimal rir;
    public Boolean isWarmup;
    public Boolean isFailure;
    public LocalDateTime completedAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
