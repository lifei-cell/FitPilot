package com.fitpilot.infrastructure.events;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record EventEnvelope(UUID eventId, String eventType, int eventVersion, String aggregateType,
                            String aggregateId, Instant occurredAt, String traceId, JsonNode payload) {
}
