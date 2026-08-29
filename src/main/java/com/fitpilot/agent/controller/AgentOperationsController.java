package com.fitpilot.agent.controller;

import com.fitpilot.agent.config.AgentProperties;
import com.fitpilot.agent.dto.AgentDtos;
import com.fitpilot.agent.infrastructure.AgentRepository;
import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.common.response.ApiResponse;
import com.fitpilot.common.operations.OperationsAuthorizer;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/operations/agent")
public class AgentOperationsController {
    private final AgentRepository repository; private final AgentProperties properties;private final OperationsAuthorizer authorizer;
    public AgentOperationsController(AgentRepository repository,AgentProperties properties,OperationsAuthorizer authorizer){this.repository=repository;this.properties=properties;this.authorizer=authorizer;}
    @GetMapping("/metrics") AgentDtos.EvaluationMetrics metrics(@RequestHeader("X-Operations-Token") String token){authorizer.authorize(token,properties.getOperationsToken());return repository.metrics();}
    @PutMapping("/executions/{id}/expected-tools") ApiResponse<Void> label(@PathVariable UUID id,@Valid @RequestBody AgentDtos.EvaluationLabel label,@RequestHeader("X-Operations-Token") String token){authorizer.authorize(token,properties.getOperationsToken());repository.label(id,label.expectedTools());return ApiResponse.success();}
}
