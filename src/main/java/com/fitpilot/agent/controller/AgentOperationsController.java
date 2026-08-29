package com.fitpilot.agent.controller;

import com.fitpilot.agent.dto.AgentDtos;
import com.fitpilot.agent.infrastructure.AgentRepository;
import com.fitpilot.common.operations.OperationsAuthorizer;
import com.fitpilot.common.operations.OperationsProperties;
import com.fitpilot.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/operations/agent")
public class AgentOperationsController {
    private final AgentRepository repository;
    private final OperationsProperties properties;
    private final OperationsAuthorizer authorizer;

    public AgentOperationsController(AgentRepository repository, OperationsProperties properties,
                                     OperationsAuthorizer authorizer) {
        this.repository = repository;
        this.properties = properties;
        this.authorizer = authorizer;
    }

    @GetMapping("/metrics")
    AgentDtos.EvaluationMetrics metrics(@RequestHeader("X-Operations-Token") String token) {
        authorize(token);
        return repository.metrics();
    }

    @PutMapping("/executions/{id}/expected-tools")
    ApiResponse<Void> label(@PathVariable UUID id, @Valid @RequestBody AgentDtos.EvaluationLabel label,
                            @RequestHeader("X-Operations-Token") String token) {
        authorize(token);
        repository.label(id, label.expectedTools());
        return ApiResponse.success();
    }

    private void authorize(String token) {
        authorizer.authorize(token, properties.getToken());
    }
}
