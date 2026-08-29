package com.fitpilot.infrastructure.events;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "fitpilot.events")
public class EventProperties {
    private boolean enabled = true;
    private final Relay relay = new Relay();
    private final Consumer consumer = new Consumer();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Relay getRelay() { return relay; }
    public Consumer getConsumer() { return consumer; }

    public static class Relay {
        private int batchSize = 100;
        private long fixedDelayMs = 500;
        private long claimTimeoutSeconds = 30;
        private long sendTimeoutSeconds = 5;
        private int maxAttempts = 20;
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public long getFixedDelayMs() { return fixedDelayMs; }
        public void setFixedDelayMs(long fixedDelayMs) { this.fixedDelayMs = fixedDelayMs; }
        public long getClaimTimeoutSeconds() { return claimTimeoutSeconds; }
        public void setClaimTimeoutSeconds(long claimTimeoutSeconds) { this.claimTimeoutSeconds = claimTimeoutSeconds; }
        public long getSendTimeoutSeconds() { return sendTimeoutSeconds; }
        public void setSendTimeoutSeconds(long sendTimeoutSeconds) { this.sendTimeoutSeconds = sendTimeoutSeconds; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    }

    public static class Consumer {
        private int concurrency = 3;
        private long retryIntervalMs = 1000;
        private long maxAttempts = 3;
        public int getConcurrency() { return concurrency; }
        public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
        public long getRetryIntervalMs() { return retryIntervalMs; }
        public void setRetryIntervalMs(long retryIntervalMs) { this.retryIntervalMs = retryIntervalMs; }
        public long getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(long maxAttempts) { this.maxAttempts = maxAttempts; }
    }
}
