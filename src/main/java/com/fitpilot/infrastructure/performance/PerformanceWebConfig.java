package com.fitpilot.infrastructure.performance;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class PerformanceWebConfig implements WebMvcConfigurer {
    private final ApiRateLimitInterceptor interceptor;
    public PerformanceWebConfig(ApiRateLimitInterceptor interceptor) { this.interceptor = interceptor; }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/v1/**");
    }
}
