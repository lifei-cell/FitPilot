package com.fitpilot.agent.memory;

import com.fitpilot.agent.config.AgentProperties;
import com.fitpilot.agent.infrastructure.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AgentConversationRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(AgentConversationRetentionJob.class);
    private final AgentRepository repository;
    private final AgentProperties properties;

    public AgentConversationRetentionJob(AgentRepository repository, AgentProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Scheduled(cron = "${fitpilot.agent.retention-cron:0 30 3 * * *}")
    void cleanup() {
        int removed = repository.deleteExpiredSessions(properties.getRetentionDays(),
                properties.getRetentionBatchSize());
        if (removed > 0) log.info("operation=AgentConversationRetention removed={}", removed);
    }
}
