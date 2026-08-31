package com.fitpilot.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "fitpilot.agent")
@Component
public class AgentProperties {
    private boolean enabled = true;
    private long sessionTtlSeconds = 7200;
    private long confirmationTtlSeconds = 600;
    private int maxMessages = 30;
    private int retentionDays = 180;
    private int retentionBatchSize = 100;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getSessionTtlSeconds() { return sessionTtlSeconds; }
    public void setSessionTtlSeconds(long value) { sessionTtlSeconds = value; }
    public long getConfirmationTtlSeconds() { return confirmationTtlSeconds; }
    public void setConfirmationTtlSeconds(long value) { confirmationTtlSeconds = value; }
    public int getMaxMessages() { return maxMessages; }
    public void setMaxMessages(int value) { maxMessages = value; }
    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int value) { retentionDays = value; }
    public int getRetentionBatchSize() { return retentionBatchSize; }
    public void setRetentionBatchSize(int value) { retentionBatchSize = value; }
}
