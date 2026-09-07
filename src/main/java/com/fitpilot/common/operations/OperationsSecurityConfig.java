package com.fitpilot.common.operations;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OperationsSecurityConfig {
    @Bean
    FilterRegistrationBean<OperationsAuthenticationFilter> operationsAuthenticationFilterRegistration(
            OperationsAuthenticationFilter filter) {
        FilterRegistrationBean<OperationsAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
