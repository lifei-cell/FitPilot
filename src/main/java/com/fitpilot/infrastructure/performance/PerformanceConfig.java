package com.fitpilot.infrastructure.performance;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(PerformanceProperties.class)
public class PerformanceConfig {
    @Bean
    Cache<String, String> hotDataLocalCache(PerformanceProperties properties) {
        return Caffeine.newBuilder()
                .maximumSize(properties.cache().l1MaximumSize())
                .expireAfterWrite(Duration.ofSeconds(properties.cache().l1TtlSeconds()))
                .recordStats()
                .build();
    }
}
