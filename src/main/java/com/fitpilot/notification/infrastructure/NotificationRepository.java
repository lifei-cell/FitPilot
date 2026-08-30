package com.fitpilot.notification.infrastructure;

import com.fitpilot.infrastructure.events.EventPayloads;
import com.fitpilot.notification.dto.NotificationView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class NotificationRepository {
    private final JdbcTemplate jdbc;
    public NotificationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(UUID sourceEventId, EventPayloads.PersonalRecordCreated payload) {
        jdbc.update("""
                INSERT INTO user_notification(user_id, source_event_id, type, title, message)
                VALUES (?, ?, 'PERSONAL_RECORD', '新的个人纪录', ?)
                ON CONFLICT (source_event_id) DO NOTHING
                """, payload.userId(), sourceEventId,
                "恭喜你刷新了 " + payload.exerciseName() + " 的 " + payload.recordType() + " 纪录！");
    }

    public List<NotificationView> findByUser(long userId, int limit) {
        return jdbc.query("""
                SELECT id, type, title, message, is_read, created_at FROM user_notification
                WHERE user_id=? ORDER BY created_at DESC LIMIT ?
                """, (rs, rowNum) -> new NotificationView(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getBoolean(5), rs.getTimestamp(6).toLocalDateTime()), userId, limit);
    }

    public boolean markRead(long userId, long id) {
        return jdbc.update("UPDATE user_notification SET is_read=TRUE WHERE id=? AND user_id=?", id, userId) == 1;
    }

    public long unreadCount(long userId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_notification WHERE user_id=? AND is_read=FALSE", Long.class, userId);
        return count == null ? 0 : count;
    }

    public int markAllRead(long userId) {
        return jdbc.update("UPDATE user_notification SET is_read=TRUE WHERE user_id=? AND is_read=FALSE", userId);
    }
}
