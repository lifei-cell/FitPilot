package com.fitpilot.notification.controller;

import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.common.response.ApiResponse;
import com.fitpilot.common.security.CurrentUser;
import com.fitpilot.notification.dto.NotificationView;
import com.fitpilot.notification.infrastructure.NotificationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationRepository repository;
    public NotificationController(NotificationRepository repository) { this.repository = repository; }

    @GetMapping
    ApiResponse<List<NotificationView>> list(@RequestParam(defaultValue = "50") int limit, Authentication auth) {
        return ApiResponse.success(repository.findByUser(CurrentUser.id(auth), Math.min(Math.max(limit, 1), 100)));
    }

    @PostMapping("/{id}/read")
    ApiResponse<Void> markRead(@PathVariable long id, Authentication auth) {
        if (!repository.markRead(CurrentUser.id(auth), id)) {
            throw new BusinessException(ErrorCode.EVENT_NOT_FOUND, "notification not found", HttpStatus.NOT_FOUND);
        }
        return ApiResponse.success(null);
    }
}
