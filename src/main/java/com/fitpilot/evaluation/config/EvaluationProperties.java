package com.fitpilot.evaluation.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "fitpilot.evaluation")
public class EvaluationProperties {
    private int corePoolSize = 1;
    private int maxPoolSize = 2;
    private int queueCapacity = 10;
    private int runTimeoutSeconds = 600;
    private int leaseSeconds = 60;
    private int heartbeatSeconds = 10;
    private int recoveryDelayMs = 5000;
    private int recoveryBatchSize = 20;
    private int ragExperimentMaxDocuments = 200;

    @PostConstruct
    void validate() {
        if (corePoolSize < 1 || maxPoolSize < corePoolSize || queueCapacity < 0) {
            throw new IllegalStateException("invalid evaluation executor configuration");
        }
        if (runTimeoutSeconds < 1 || leaseSeconds < 2 || heartbeatSeconds < 1
                || heartbeatSeconds >= leaseSeconds || recoveryDelayMs < 100 || recoveryBatchSize < 1
                || ragExperimentMaxDocuments < 1 || ragExperimentMaxDocuments > 1000) {
            throw new IllegalStateException("invalid evaluation lifecycle configuration");
        }
    }

    public int getCorePoolSize() { return corePoolSize; }
    public void setCorePoolSize(int value) { corePoolSize = value; }
    public int getMaxPoolSize() { return maxPoolSize; }
    public void setMaxPoolSize(int value) { maxPoolSize = value; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int value) { queueCapacity = value; }
    public int getRunTimeoutSeconds() { return runTimeoutSeconds; }
    public void setRunTimeoutSeconds(int value) { runTimeoutSeconds = value; }
    public int getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(int value) { leaseSeconds = value; }
    public int getHeartbeatSeconds() { return heartbeatSeconds; }
    public void setHeartbeatSeconds(int value) { heartbeatSeconds = value; }
    public int getRecoveryDelayMs() { return recoveryDelayMs; }
    public void setRecoveryDelayMs(int value) { recoveryDelayMs = value; }
    public int getRecoveryBatchSize() { return recoveryBatchSize; }
    public void setRecoveryBatchSize(int value) { recoveryBatchSize = value; }
    public int getRagExperimentMaxDocuments() { return ragExperimentMaxDocuments; }
    public void setRagExperimentMaxDocuments(int value) { ragExperimentMaxDocuments = value; }
}
