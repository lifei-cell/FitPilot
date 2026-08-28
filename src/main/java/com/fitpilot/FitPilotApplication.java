package com.fitpilot;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@MapperScan({"com.fitpilot.user.infrastructure", "com.fitpilot.exercise.infrastructure",
        "com.fitpilot.plan.infrastructure", "com.fitpilot.workout.infrastructure",
        "com.fitpilot.pr.infrastructure", "com.fitpilot.analytics.infrastructure"})
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class FitPilotApplication {
    public static void main(String[] args) {
        SpringApplication.run(FitPilotApplication.class, args);
    }
}
