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
    private String operationsToken = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getSessionTtlSeconds() { return sessionTtlSeconds; }
    public void setSessionTtlSeconds(long value) { sessionTtlSeconds = value; }
    public long getConfirmationTtlSeconds() { return confirmationTtlSeconds; }
    public void setConfirmationTtlSeconds(long value) { confirmationTtlSeconds = value; }
    public int getMaxMessages() { return maxMessages; }
    public void setMaxMessages(int value) { maxMessages = value; }
    public String getOperationsToken() { return operationsToken; }
    public void setOperationsToken(String value) { operationsToken = value; }
}
