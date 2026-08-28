package com.fitpilot.infrastructure.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "fitpilot.events", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PersonalRecordEventConsumer {
    private final PersonalRecordEventHandler handler;
    public PersonalRecordEventConsumer(PersonalRecordEventHandler handler) { this.handler = handler; }

    @KafkaListener(topics = EventTopics.PERSONAL_RECORD_CREATED,
            groupId = PersonalRecordEventHandler.NOTIFICATION_CONSUMER,
            concurrency = "${fitpilot.events.consumer.concurrency:3}")
    public void notification(String event) { handler.notifyUser(event); }
}
