package com.fitpilot.infrastructure.events;

import com.fitpilot.common.response.ApiResponse;
import com.fitpilot.common.operations.OperationsAuthorizer;
import com.fitpilot.common.operations.OperationsProperties;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/operations/events")
public class EventOperationsController {
    private final DeadLetterService service;
    private final OperationsProperties properties;
    private final EventStatusService statusService;
    private final OperationsAuthorizer authorizer;

    public EventOperationsController(DeadLetterService service, OperationsProperties properties,
                                     EventStatusService statusService, OperationsAuthorizer authorizer) {
        this.service = service;
        this.properties = properties;
        this.statusService = statusService;
        this.authorizer = authorizer;
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
        authorizer.authorize(candidate,properties.getToken());
    }
}
