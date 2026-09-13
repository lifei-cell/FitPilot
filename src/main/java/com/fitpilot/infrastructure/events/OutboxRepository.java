package com.fitpilot.infrastructure.events;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbc;
    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(EventEnvelope envelope, String topic, String eventKey, String payload) {
        jdbc.update("""
                INSERT INTO outbox_event(event_id, aggregate_type, aggregate_id, event_type, topic, event_key, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                """, envelope.eventId(), envelope.aggregateType(), envelope.aggregateId(), envelope.eventType(),
                topic, eventKey, payload);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimBatch(int batchSize, long claimTimeoutSeconds, int maxAttempts) {
        return jdbc.query("""
                WITH candidates AS (
                    SELECT id FROM outbox_event
                    WHERE retry_count < ? AND (
                        (status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP)
                        OR (status = 'SENDING' AND claimed_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 second'))
                    )
                    ORDER BY id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                ), claimed AS (
                    UPDATE outbox_event o
                    SET status='SENDING', claimed_at=CURRENT_TIMESTAMP, claim_token=gen_random_uuid()
                    FROM candidates c WHERE o.id=c.id
                    RETURNING o.id, o.topic, o.event_key, o.payload::text, o.retry_count, o.claim_token
                )
                SELECT * FROM claimed
                ORDER BY id
                """, (rs, rowNum) -> new OutboxEvent(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getInt(5), rs.getObject(6, UUID.class)), maxAttempts, claimTimeoutSeconds, batchSize);
    }

    /** Refresh just before sending: queued batch entries may already have been reclaimed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean refreshClaim(OutboxEvent event) {
        return jdbc.update("""
                UPDATE outbox_event SET claimed_at=CURRENT_TIMESTAMP
                WHERE id=? AND status='SENDING' AND claim_token=?
                """, event.id(), event.claimToken()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markSent(OutboxEvent event) {
        return jdbc.update("""
                UPDATE outbox_event SET status='SENT', sent_at=CURRENT_TIMESTAMP, claimed_at=NULL,
                    claim_token=NULL, last_error=NULL
                WHERE id=? AND status='SENDING' AND claim_token=?
                """, event.id(), event.claimToken()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(OutboxEvent event, Throwable failure, int maxAttempts) {
        int attempts = event.retryCount() + 1;
        String status = attempts >= maxAttempts ? "FAILED" : "PENDING";
        long delaySeconds = Math.min(300, 1L << Math.min(attempts, 8));
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return jdbc.update("""
                UPDATE outbox_event SET status=?, retry_count=?,
                    next_attempt_at=CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    claimed_at=NULL, claim_token=NULL, last_error=?
                WHERE id=? AND status='SENDING' AND claim_token=?
                """, status, attempts, delaySeconds, message.substring(0, Math.min(message.length(), 1000)),
                event.id(), event.claimToken()) == 1;
    }

    public int replayFailed(UUID eventId) {
        return jdbc.update("""
                UPDATE outbox_event SET status='PENDING', retry_count=0, next_attempt_at=CURRENT_TIMESTAMP,
                    claimed_at=NULL, claim_token=NULL, last_error=NULL WHERE event_id=? AND status='FAILED'
                """, eventId);
    }
    public long pendingCount(){Long value=jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE status IN ('PENDING','SENDING')",Long.class);return value==null?0:value;}
    public long oldestPendingAgeSeconds(){Long value=jdbc.queryForObject("SELECT COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP-MIN(created_at)))::bigint,0) FROM outbox_event WHERE status IN ('PENDING','SENDING')",Long.class);return value==null?0:Math.max(0,value);}
}
