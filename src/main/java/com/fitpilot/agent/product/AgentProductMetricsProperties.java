package com.fitpilot.agent.product;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Component
@Validated
@ConfigurationProperties(prefix = "fitpilot.agent.product-metrics")
public class AgentProductMetricsProperties {
    @Min(14)
    @Max(365)
    private int windowDays = 28;
    @Min(7)
    @Max(90)
    private int outcomeWindowDays = 28;

    public int getWindowDays() {
        return windowDays;
    }

    public void setWindowDays(int windowDays) {
        this.windowDays = windowDays;
    }

    public int getOutcomeWindowDays() {
        return outcomeWindowDays;
    }

    public void setOutcomeWindowDays(int outcomeWindowDays) {
        this.outcomeWindowDays = outcomeWindowDays;
    }
}
