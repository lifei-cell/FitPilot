package com.fitpilot.user.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("user_profile")
public class UserProfile {
    @TableId(type = IdType.AUTO) public Long id;
    public Long userId;
    public Short gender;
    public LocalDate birthday;
    public BigDecimal heightCm;
    public Integer trainingExperienceMonths;
    public String trainingGoal;
    public Integer weeklyFrequency;
    public Integer preferredDurationMinutes;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
