package com.fitpilot.workout.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("workout_exercise")
public class WorkoutExercise {
    @TableId(type = IdType.AUTO) public Long id;
    public Long workoutId;
    public Long exerciseId;
    public Integer sequence;
    public String exerciseName;
    public Integer targetSets;
    public Integer targetRepsMin;
    public Integer targetRepsMax;
    public BigDecimal targetRpe;
    public Integer restSeconds;
    public String notes;
    public LocalDateTime createdAt;
}
