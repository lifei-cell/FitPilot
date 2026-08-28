package com.fitpilot.user.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("users")
public class User {
    @TableId(type = IdType.AUTO) public Long id;
    public String username;
    public String email;
    public String passwordHash;
    public Short status;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
