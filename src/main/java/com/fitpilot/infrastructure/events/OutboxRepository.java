package com.fitpilot.infrastructure.events;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public OutboxRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
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
        List<OutboxEvent> rows = jdbc.query("""
                SELECT id, topic, event_key, payload::text, retry_count
                FROM outbox_event
                WHERE retry_count < ? AND (
                    (status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP)
                    OR (status = 'SENDING' AND claimed_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 second'))
                )
                ORDER BY id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
                """, (rs, rowNum) -> new OutboxEvent(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getInt(5)), maxAttempts, claimTimeoutSeconds, batchSize);
        if (!rows.isEmpty()) {
            namedJdbc.update("UPDATE outbox_event SET status='SENDING', claimed_at=CURRENT_TIMESTAMP WHERE id IN (:ids)",
                    Map.of("ids", rows.stream().map(OutboxEvent::id).toList()));
        }
        return rows;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(long id) {
        jdbc.update("""
                UPDATE outbox_event SET status='SENT', sent_at=CURRENT_TIMESTAMP, claimed_at=NULL,
                    last_error=NULL WHERE id=? AND status='SENDING'
                """, id);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(OutboxEvent event, Throwable failure, int maxAttempts) {
        int attempts = event.retryCount() + 1;
        String status = attempts >= maxAttempts ? "FAILED" : "PENDING";
        long delaySeconds = Math.min(300, 1L << Math.min(attempts, 8));
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        jdbc.update("""
                UPDATE outbox_event SET status=?, retry_count=?, next_attempt_at=?, claimed_at=NULL,
                    last_error=? WHERE id=?
                """, new Object[]{status, attempts, LocalDateTime.now().plusSeconds(delaySeconds),
                message.substring(0, Math.min(message.length(), 1000)), event.id()},
                new int[]{Types.VARCHAR, Types.INTEGER, Types.TIMESTAMP, Types.VARCHAR, Types.BIGINT});
    }

    public int replayFailed(UUID eventId) {
        return jdbc.update("""
                UPDATE outbox_event SET status='PENDING', retry_count=0, next_attempt_at=CURRENT_TIMESTAMP,
                    claimed_at=NULL, last_error=NULL WHERE event_id=? AND status='FAILED'
                """, eventId);
    }
    public long pendingCount(){Long value=jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE status IN ('PENDING','SENDING')",Long.class);return value==null?0:value;}
    public long oldestPendingAgeSeconds(){Long value=jdbc.queryForObject("SELECT COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP-MIN(created_at)))::bigint,0) FROM outbox_event WHERE status IN ('PENDING','SENDING')",Long.class);return value==null?0:Math.max(0,value);}
}
