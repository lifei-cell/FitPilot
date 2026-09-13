package com.fitpilot.infrastructure.events;

import java.util.UUID;

public record OutboxEvent(long id, String topic, String eventKey, String payload, int retryCount, UUID claimToken) {
}
