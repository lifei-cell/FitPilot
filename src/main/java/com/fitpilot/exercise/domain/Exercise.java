package com.fitpilot.exercise.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("exercise")
public class Exercise {
    @TableId(type = IdType.AUTO) public Long id;
    public String name;
    public String englishName;
    public String category;
    public String equipment;
    public String difficulty;
    public String primaryMuscle;
    public String secondaryMuscles;
    public String description;
    public String instructions;
    public Short status;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
