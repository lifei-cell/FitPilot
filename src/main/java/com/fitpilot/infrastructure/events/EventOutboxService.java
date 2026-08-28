package com.fitpilot.infrastructure.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class EventOutboxService {
    private final ObjectMapper objectMapper;
    private final OutboxRepository repository;

    public EventOutboxService(ObjectMapper objectMapper, OutboxRepository repository) {
        this.objectMapper = objectMapper;
        this.repository = repository;
    }

    public UUID append(String aggregateType, long aggregateId, String eventType, Object payload) {
        UUID eventId = UUID.randomUUID();
        EventEnvelope envelope = new EventEnvelope(eventId, eventType, 1, aggregateType,
                String.valueOf(aggregateId), Instant.now(), MDC.get("requestId"), objectMapper.valueToTree(payload));
        try {
            repository.insert(envelope, EventTypes.topicFor(eventType), String.valueOf(aggregateId),
                    objectMapper.writeValueAsString(envelope));
            return eventId;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("cannot serialize domain event", ex);
        }
    }
}
