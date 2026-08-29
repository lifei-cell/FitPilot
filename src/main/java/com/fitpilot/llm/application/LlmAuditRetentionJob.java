package com.fitpilot.llm.application;

import com.fitpilot.llm.infrastructure.LlmInvocationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class LlmAuditRetentionJob {
    private final LlmInvocationRepository repository; private final int retentionDays;
    public LlmAuditRetentionJob(LlmInvocationRepository repository,@Value("${fitpilot.llm.audit.retention-days:30}") int retentionDays){this.repository=repository;this.retentionDays=Math.max(1,retentionDays);}
    @Scheduled(initialDelayString="${fitpilot.llm.audit.cleanup-initial-delay-ms:60000}",fixedDelayString="${fitpilot.llm.audit.cleanup-delay-ms:86400000}")
    public void cleanup(){repository.deleteOlderThan(LocalDateTime.now().minusDays(retentionDays));}
}
