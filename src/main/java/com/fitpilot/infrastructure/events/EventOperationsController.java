package com.fitpilot.infrastructure.events;

import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.common.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/operations/events")
public class EventOperationsController {
    private final DeadLetterService service;
    private final EventProperties properties;
    private final EventStatusService statusService;

    public EventOperationsController(DeadLetterService service, EventProperties properties,
                                     EventStatusService statusService) {
        this.service = service;
        this.properties = properties;
        this.statusService = statusService;
    }

    @GetMapping("/status")
    ApiResponse<EventStatusService.EventStatus> status(@RequestHeader("X-Operations-Token") String token) {
        authorize(token);
        return ApiResponse.success(statusService.status());
    }

    @GetMapping("/dead-letters")
    ApiResponse<List<DeadLetterEvent>> list(@RequestHeader("X-Operations-Token") String token,
                                            @RequestParam(defaultValue = "50") int limit) {
        authorize(token);
        return ApiResponse.success(service.list(limit));
    }

    @PostMapping("/dead-letters/{id}/replay")
    ApiResponse<Void> replay(@RequestHeader("X-Operations-Token") String token, @PathVariable UUID id) {
        authorize(token);
        service.replay(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/outbox/{eventId}/replay")
    ApiResponse<Void> replayOutbox(@RequestHeader("X-Operations-Token") String token, @PathVariable UUID eventId) {
        authorize(token);
        service.replayOutbox(eventId);
        return ApiResponse.success(null);
    }

    private void authorize(String candidate) {
        String expected = properties.getOperationsToken();
        boolean valid = expected != null && !expected.isBlank() && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
        if (!valid) throw new BusinessException(ErrorCode.ACCESS_DENIED, "invalid operations token", HttpStatus.FORBIDDEN);
    }
}
