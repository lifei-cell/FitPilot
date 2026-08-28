package com.fitpilot.plan.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("training_plan_exercise")
public class TrainingPlanExercise {
    @TableId(type = IdType.AUTO) public Long id;
    public Long trainingPlanDayId;
    public Long exerciseId;
    public Integer sequence;
    public Integer targetSets;
    public Integer targetRepsMin;
    public Integer targetRepsMax;
    public BigDecimal targetRpe;
    public Integer restSeconds;
    public String notes;
    public LocalDateTime createdAt;
}
