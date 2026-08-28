package com.fitpilot.user.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("body_metric")
public class BodyMetric {
    @TableId(type = IdType.AUTO) public Long id;
    public Long userId;
    public BigDecimal weightKg;
    public BigDecimal bodyFatPercentage;
    public BigDecimal muscleMassKg;
    public LocalDateTime recordedAt;
    public LocalDateTime createdAt;
}
