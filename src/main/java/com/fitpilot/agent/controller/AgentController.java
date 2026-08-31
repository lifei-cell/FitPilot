package com.fitpilot.agent.controller;

import com.fitpilot.agent.application.AgentWorkflowService;
import com.fitpilot.agent.dto.AgentDtos;
import com.fitpilot.agent.memory.AgentSessionStore;
import com.fitpilot.common.response.ApiResponse;
import com.fitpilot.common.response.PageResult;
import com.fitpilot.common.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1/agent")
public class AgentController {
    private final AgentWorkflowService service;
    public AgentController(AgentWorkflowService service){this.service=service;}
    @PostMapping("/sessions") @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<AgentDtos.SessionView> session(Authentication auth){return ApiResponse.success(service.createSession(CurrentUser.id(auth)));}
    @GetMapping("/sessions")
    ApiResponse<PageResult<AgentDtos.SessionSummary>> sessions(@RequestParam(required=false) String status,
            @RequestParam(defaultValue="1") @Min(1) long page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) long size, Authentication auth) {
        return ApiResponse.success(service.sessions(CurrentUser.id(auth),status,page,size));
    }
    @PatchMapping("/sessions/{id}")
    ApiResponse<Void> updateSession(@PathVariable UUID id,@Valid @RequestBody AgentDtos.SessionUpdateRequest request,
                                    Authentication auth) {
        service.updateSession(CurrentUser.id(auth),id,request);return ApiResponse.success();
    }
    @DeleteMapping("/sessions/{id}")
    ApiResponse<Void> deleteSession(@PathVariable UUID id,Authentication auth) {
        service.deleteSession(CurrentUser.id(auth),id);return ApiResponse.success();
    }
    @PostMapping("/sessions/{id}/messages")
    ApiResponse<AgentDtos.MessageView> message(@PathVariable UUID id,@Valid @RequestBody AgentDtos.MessageRequest request,Authentication auth){return ApiResponse.success(service.message(CurrentUser.id(auth),id,request));}
    @GetMapping("/sessions/{id}/messages")
    ApiResponse<List<AgentSessionStore.Message>> messages(@PathVariable UUID id,Authentication auth){return ApiResponse.success(service.messages(CurrentUser.id(auth),id));}
    @GetMapping("/sessions/{id}/history")
    ApiResponse<AgentDtos.MessagePage> history(@PathVariable UUID id,@RequestParam(required=false) Long beforeId,
            @RequestParam(defaultValue="50") @Min(1) @Max(100) int limit,Authentication auth) {
        return ApiResponse.success(service.history(CurrentUser.id(auth),id,beforeId,limit));
    }
    @GetMapping("/sessions/{id}/pending-actions")
    ApiResponse<List<AgentDtos.PendingActionSummary>> pendingActions(@PathVariable UUID id,Authentication auth) {
        return ApiResponse.success(service.pendingActions(CurrentUser.id(auth),id));
    }
    @PostMapping("/pending-actions/{id}/confirmation-token")
    ApiResponse<AgentDtos.ConfirmationTokenView> confirmationToken(@PathVariable UUID id,Authentication auth) {
        return ApiResponse.success(service.rotateConfirmationToken(CurrentUser.id(auth),id));
    }
    @PostMapping("/pending-actions/{id}/confirm")
    ApiResponse<Object> confirm(@PathVariable UUID id,@Valid @RequestBody AgentDtos.ConfirmRequest request,Authentication auth){return ApiResponse.success(service.confirm(CurrentUser.id(auth),id,request.confirmationToken()));}
    @PutMapping("/preferences")
    ApiResponse<Void> preference(@Valid @RequestBody AgentDtos.PreferenceRequest request,Authentication auth){service.savePreference(CurrentUser.id(auth),request);return ApiResponse.success();}
    @GetMapping("/preferences")
    ApiResponse<List<AgentDtos.PreferenceView>> preferences(Authentication auth){return ApiResponse.success(service.preferences(CurrentUser.id(auth)));}
}
