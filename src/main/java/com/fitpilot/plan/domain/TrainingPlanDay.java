package com.fitpilot.plan.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("training_plan_day")
public class TrainingPlanDay {
    @TableId(type = IdType.AUTO) public Long id;
    public Long trainingPlanId;
    public Integer dayNumber;
    public String name;
    public String notes;
    public LocalDateTime createdAt;
}
