package com.fitpilot.notification.dto;

import java.time.LocalDateTime;

public record NotificationView(long id, String type, String title, String message,
                               boolean read, LocalDateTime createdAt) {
}
