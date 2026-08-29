package com.fitpilot.evaluation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class EvaluationConfig {
    @Bean("evaluationExecutor")
    TaskExecutor evaluationExecutor(){ThreadPoolTaskExecutor executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(1);executor.setMaxPoolSize(2);executor.setQueueCapacity(10);executor.setThreadNamePrefix("fitpilot-eval-");executor.initialize();return executor;}
}
