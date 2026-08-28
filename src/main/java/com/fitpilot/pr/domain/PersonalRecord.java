package com.fitpilot.pr.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("personal_record")
public class PersonalRecord {
    @TableId(type = IdType.AUTO) public Long id;
    public Long userId;
    public Long exerciseId;
    public String recordType;
    public BigDecimal weightKg;
    public Integer reps;
    @TableField("estimated_1rm") public BigDecimal estimated1rm;
    public Long workoutId;
    public Long workoutSetId;
    public LocalDateTime achievedAt;
    public LocalDateTime createdAt;
}
