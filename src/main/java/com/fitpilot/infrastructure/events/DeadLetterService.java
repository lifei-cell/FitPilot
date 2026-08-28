package com.fitpilot.infrastructure.events;

import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class DeadLetterService {
    private final DeadLetterRepository repository;
    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;

    public DeadLetterService(DeadLetterRepository repository, OutboxRepository outbox,
                             KafkaTemplate<String, String> kafka) {
        this.repository = repository;
        this.outbox = outbox;
        this.kafka = kafka;
    }

    public List<DeadLetterEvent> list(int limit) { return repository.findOpen(Math.min(Math.max(limit, 1), 100)); }

    public void replay(UUID id) {
        DeadLetterEvent event = repository.findOpen(id).orElseThrow(() -> new BusinessException(
                ErrorCode.EVENT_NOT_FOUND, "open dead-letter event not found", HttpStatus.NOT_FOUND));
        try {
            kafka.send(event.originalTopic(), event.eventKey(), event.payload()).get(10, TimeUnit.SECONDS);
            repository.markReplayed(id);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.EVENT_REPLAY_FAILED, "dead-letter replay failed", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    public void replayOutbox(UUID eventId) {
        if (outbox.replayFailed(eventId) == 0) {
            throw new BusinessException(ErrorCode.EVENT_NOT_FOUND, "failed outbox event not found", HttpStatus.NOT_FOUND);
        }
    }
}
