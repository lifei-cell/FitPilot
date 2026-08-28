package com.fitpilot.infrastructure.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "fitpilot.events", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WorkoutEventConsumers {
    private final WorkoutCompletedEventHandler handler;
    public WorkoutEventConsumers(WorkoutCompletedEventHandler handler) { this.handler = handler; }

    @KafkaListener(topics = EventTopics.WORKOUT_COMPLETED, groupId = WorkoutCompletedEventHandler.PR_CONSUMER,
            concurrency = "${fitpilot.events.consumer.concurrency:3}")
    public void personalRecords(String event) { handler.projectPersonalRecords(event); }

    @KafkaListener(topics = EventTopics.WORKOUT_COMPLETED, groupId = WorkoutCompletedEventHandler.ANALYTICS_CONSUMER,
            concurrency = "${fitpilot.events.consumer.concurrency:3}")
    public void analytics(String event) { handler.projectAnalytics(event); }
}
