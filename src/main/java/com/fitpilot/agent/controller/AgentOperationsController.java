package com.fitpilot.agent.controller;

import com.fitpilot.agent.config.AgentProperties;
import com.fitpilot.agent.dto.AgentDtos;
import com.fitpilot.agent.infrastructure.AgentRepository;
import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/operations/agent")
public class AgentOperationsController {
    private final AgentRepository repository; private final AgentProperties properties;
    public AgentOperationsController(AgentRepository repository,AgentProperties properties){this.repository=repository;this.properties=properties;}
    @GetMapping("/metrics") AgentDtos.EvaluationMetrics metrics(@RequestHeader("X-Operations-Token") String token){authorize(token);return repository.metrics();}
    @PutMapping("/executions/{id}/expected-tools") ApiResponse<Void> label(@PathVariable UUID id,@Valid @RequestBody AgentDtos.EvaluationLabel label,@RequestHeader("X-Operations-Token") String token){authorize(token);repository.label(id,label.expectedTools());return ApiResponse.success();}
    private void authorize(String supplied){String expected=properties.getOperationsToken();if(expected==null||expected.isBlank()||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))throw new BusinessException(ErrorCode.ACCESS_DENIED,"invalid operations token",HttpStatus.FORBIDDEN);}
}
