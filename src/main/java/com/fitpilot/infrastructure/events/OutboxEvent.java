package com.fitpilot.infrastructure.events;

public record OutboxEvent(long id, String topic, String eventKey, String payload, int retryCount) {
}
