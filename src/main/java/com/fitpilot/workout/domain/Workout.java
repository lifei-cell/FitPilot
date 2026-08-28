package com.fitpilot.workout.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("workout")
public class Workout {
    @TableId(type = IdType.AUTO) public Long id;
    public Long userId;
    public Long trainingPlanId;
    public Long trainingPlanDayId;
    public String name;
    public String status;
    public LocalDateTime startedAt;
    public LocalDateTime completedAt;
    public Integer durationSeconds;
    public String notes;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
