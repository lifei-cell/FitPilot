package com.fitpilot.infrastructure.scheduling;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class BackgroundSchedulingConfig {
    // Spring selects this bean for @Scheduled methods that have no scheduler qualifier.
    @Bean
    ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("fitpilot-background-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
