package com.fitpilot.infrastructure.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(prefix = "fitpilot.events", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KafkaEventConfig {
    private static final int PARTITIONS = 3;

    @Bean NewTopic workoutCompletedTopic() { return topic(EventTopics.WORKOUT_COMPLETED); }
    @Bean NewTopic workoutCompletedDlt() { return topic(EventTopics.WORKOUT_COMPLETED + ".DLT"); }
    @Bean NewTopic personalRecordCreatedTopic() { return topic(EventTopics.PERSONAL_RECORD_CREATED); }
    @Bean NewTopic personalRecordCreatedDlt() { return topic(EventTopics.PERSONAL_RECORD_CREATED + ".DLT"); }
    @Bean NewTopic trainingPlanCreatedTopic() { return topic(EventTopics.TRAINING_PLAN_CREATED); }
    @Bean NewTopic trainingPlanUpdatedTopic() { return topic(EventTopics.TRAINING_PLAN_UPDATED); }

    @Bean
    CommonErrorHandler eventErrorHandler(KafkaTemplate<String, String> kafka, DeadLetterRepository deadLetters,
                                         EventProperties properties) {
        DeadLetterPublishingRecoverer publisher = new DeadLetterPublishingRecoverer(kafka,
                (record, failure) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler((record, failure) -> {
            deadLetters.save(record, failure);
            publisher.accept(record, failure);
        }, new FixedBackOff(properties.getConsumer().getRetryIntervalMs(),
                Math.max(0, properties.getConsumer().getMaxAttempts() - 1)));
        handler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
        return handler;
    }

    private NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(PARTITIONS).replicas(1).build();
    }
}
