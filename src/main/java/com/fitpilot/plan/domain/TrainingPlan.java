package com.fitpilot.plan.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("training_plan")
public class TrainingPlan {
    @TableId(type = IdType.AUTO) public Long id;
    public Long userId;
    public String name;
    public String description;
    public String goal;
    public Integer durationWeeks;
    public Integer daysPerWeek;
    public String status;
    public Integer version;
    public LocalDate startedAt;
    public LocalDate endedAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
