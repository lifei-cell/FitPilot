package com.fitpilot.infrastructure.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class EventEnvelopeReader {
    private final ObjectMapper objectMapper;
    public EventEnvelopeReader(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    public <T> ParsedEvent<T> read(String raw, String expectedType, Class<T> payloadType) {
        try {
            EventEnvelope envelope = objectMapper.readValue(raw, EventEnvelope.class);
            if (envelope.eventId() == null || envelope.eventVersion() != 1 || !expectedType.equals(envelope.eventType())) {
                throw new IllegalArgumentException("invalid event envelope for " + expectedType);
            }
            return new ParsedEvent<>(envelope, objectMapper.treeToValue(envelope.payload(), payloadType));
        } catch (Exception ex) {
            throw new IllegalArgumentException("cannot read " + expectedType, ex);
        }
    }

    public record ParsedEvent<T>(EventEnvelope envelope, T payload) {}
}
