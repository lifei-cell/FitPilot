package com.fitpilot.infrastructure.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DeadLetterRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DeadLetterRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void save(ConsumerRecord<?, ?> record, Exception failure) {
        String payload = String.valueOf(record.value());
        String reason = failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage();
        jdbc.update("""
                INSERT INTO dead_letter_event(id, event_id, original_topic, event_key, payload,
                    partition_id, offset_id, failure_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), extractEventId(payload), record.topic(), String.valueOf(record.key()), payload,
                record.partition(), record.offset(), reason.substring(0, Math.min(reason.length(), 2000)));
    }

    public List<DeadLetterEvent> findOpen(int limit) {
        return jdbc.query("""
                SELECT id, event_id, original_topic, event_key, payload, partition_id, offset_id,
                    failure_reason, status, failed_at, replayed_at
                FROM dead_letter_event WHERE status='OPEN' ORDER BY failed_at DESC LIMIT ?
                """, this::map, limit);
    }

    public Optional<DeadLetterEvent> findOpen(UUID id) {
        return jdbc.query("""
                SELECT id, event_id, original_topic, event_key, payload, partition_id, offset_id,
                    failure_reason, status, failed_at, replayed_at
                FROM dead_letter_event WHERE id=? AND status='OPEN'
                """, this::map, id).stream().findFirst();
    }

    public void markReplayed(UUID id) {
        jdbc.update("UPDATE dead_letter_event SET status='REPLAYED', replayed_at=CURRENT_TIMESTAMP WHERE id=? AND status='OPEN'", id);
    }

    private DeadLetterEvent map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new DeadLetterEvent(rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getString("original_topic"), rs.getString("event_key"), rs.getString("payload"),
                rs.getInt("partition_id"), rs.getLong("offset_id"), rs.getString("failure_reason"),
                rs.getString("status"), rs.getTimestamp("failed_at").toLocalDateTime(),
                rs.getTimestamp("replayed_at") == null ? null : rs.getTimestamp("replayed_at").toLocalDateTime());
    }

    private UUID extractEventId(String payload) {
        try {
            String value = objectMapper.readTree(payload).path("eventId").asText();
            return value.isBlank() ? null : UUID.fromString(value);
        } catch (Exception ignored) {
            return null;
        }
    }
}
