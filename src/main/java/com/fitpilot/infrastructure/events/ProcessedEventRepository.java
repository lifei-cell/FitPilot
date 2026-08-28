package com.fitpilot.infrastructure.events;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class ProcessedEventRepository {
    private final JdbcTemplate jdbc;
    public ProcessedEventRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean claim(UUID eventId, String consumer) {
        return jdbc.update("""
                INSERT INTO processed_event(event_id, consumer) VALUES (?, ?)
                ON CONFLICT (event_id, consumer) DO NOTHING
                """, eventId, consumer) == 1;
    }
}
