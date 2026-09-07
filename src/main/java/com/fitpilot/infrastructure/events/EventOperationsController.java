package com.fitpilot.infrastructure.events;

import com.fitpilot.common.response.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/operations/events")
public class EventOperationsController {
    private final DeadLetterService service;
    private final EventStatusService statusService;

    public EventOperationsController(DeadLetterService service, EventStatusService statusService) {
        this.service = service;
        this.statusService = statusService;
    }

    @GetMapping("/status")
    ApiResponse<EventStatusService.EventStatus> status() {
        return ApiResponse.success(statusService.status());
    }

    @GetMapping("/dead-letters")
    ApiResponse<List<DeadLetterEvent>> list(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(service.list(limit));
    }

    @PostMapping("/dead-letters/{id}/replay")
    ApiResponse<Void> replay(@PathVariable UUID id) {
        service.replay(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/outbox/{eventId}/replay")
    ApiResponse<Void> replayOutbox(@PathVariable UUID eventId) {
        service.replayOutbox(eventId);
        return ApiResponse.success(null);
    }
}
