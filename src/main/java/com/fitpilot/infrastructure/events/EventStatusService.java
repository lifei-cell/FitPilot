package com.fitpilot.infrastructure.events;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class EventStatusService {
    private final JdbcTemplate jdbc;
    public EventStatusService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public EventStatus status() {
        Map<String, Long> outbox = new LinkedHashMap<>();
        jdbc.query("SELECT status, COUNT(*) FROM outbox_event GROUP BY status ORDER BY status",
                (rs, rowNum) -> Map.entry(rs.getString(1), rs.getLong(2)))
                .forEach(entry -> outbox.put(entry.getKey(), entry.getValue()));
        Long deadLetters = jdbc.queryForObject("SELECT COUNT(*) FROM dead_letter_event WHERE status='OPEN'", Long.class);
        Long processed = jdbc.queryForObject("SELECT COUNT(*) FROM processed_event", Long.class);
        return new EventStatus(outbox, deadLetters == null ? 0 : deadLetters, processed == null ? 0 : processed);
    }

    public record EventStatus(Map<String, Long> outbox, long openDeadLetters, long processedEvents) {}
}
