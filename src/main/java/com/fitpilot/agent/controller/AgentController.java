package com.fitpilot.agent.controller;

import com.fitpilot.agent.application.AgentWorkflowService;
import com.fitpilot.agent.dto.AgentDtos;
import com.fitpilot.agent.memory.AgentSessionStore;
import com.fitpilot.common.response.ApiResponse;
import com.fitpilot.common.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {
    private final AgentWorkflowService service;
    public AgentController(AgentWorkflowService service){this.service=service;}
    @PostMapping("/sessions") @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<AgentDtos.SessionView> session(Authentication auth){return ApiResponse.success(service.createSession(CurrentUser.id(auth)));}
    @PostMapping("/sessions/{id}/messages")
    ApiResponse<AgentDtos.MessageView> message(@PathVariable UUID id,@Valid @RequestBody AgentDtos.MessageRequest request,Authentication auth){return ApiResponse.success(service.message(CurrentUser.id(auth),id,request));}
    @GetMapping("/sessions/{id}/messages")
    ApiResponse<List<AgentSessionStore.Message>> messages(@PathVariable UUID id,Authentication auth){return ApiResponse.success(service.messages(CurrentUser.id(auth),id));}
    @PostMapping("/pending-actions/{id}/confirm")
    ApiResponse<Object> confirm(@PathVariable UUID id,@Valid @RequestBody AgentDtos.ConfirmRequest request,Authentication auth){return ApiResponse.success(service.confirm(CurrentUser.id(auth),id,request.confirmationToken()));}
    @PutMapping("/preferences")
    ApiResponse<Void> preference(@Valid @RequestBody AgentDtos.PreferenceRequest request,Authentication auth){service.savePreference(CurrentUser.id(auth),request);return ApiResponse.success();}
    @GetMapping("/preferences")
    ApiResponse<List<AgentDtos.PreferenceView>> preferences(Authentication auth){return ApiResponse.success(service.preferences(CurrentUser.id(auth)));}
}
