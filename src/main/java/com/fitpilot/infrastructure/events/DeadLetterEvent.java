package com.fitpilot.infrastructure.events;

import java.time.LocalDateTime;
import java.util.UUID;

public record DeadLetterEvent(UUID id, UUID eventId, String originalTopic, String eventKey, String payload,
                              int partitionId, long offsetId, String failureReason, String status,
                              LocalDateTime failedAt, LocalDateTime replayedAt) {
}
