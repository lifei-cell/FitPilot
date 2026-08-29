package com.fitpilot.llm.controller;

import com.fitpilot.common.operations.OperationsAuthorizer;
import com.fitpilot.common.operations.OperationsProperties;
import com.fitpilot.common.response.ApiResponse;
import com.fitpilot.llm.application.LlmGateway;
import com.fitpilot.llm.dto.LlmDtos;
import com.fitpilot.llm.infrastructure.LlmInvocationRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1/operations/llm")
public class LlmOperationsController {
    private final LlmGateway gateway;
    private final LlmInvocationRepository repository;
    private final OperationsAuthorizer authorizer;
    private final OperationsProperties properties;

    public LlmOperationsController(LlmGateway gateway, LlmInvocationRepository repository,
                                   OperationsAuthorizer authorizer, OperationsProperties properties) {
        this.gateway = gateway;
        this.repository = repository;
        this.authorizer = authorizer;
        this.properties = properties;
    }

    @GetMapping("/status")
    ApiResponse<Map<String, Object>> status(@RequestHeader("X-Operations-Token") String token) {
        authorize(token);
        return ApiResponse.success(gateway.status());
    }

    @GetMapping("/invocations")
    ApiResponse<List<LlmDtos.InvocationView>> invocations(
            @RequestHeader("X-Operations-Token") String token,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String model,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        authorize(token);
        return ApiResponse.success(repository.list(blank(status), blank(model), limit));
    }

    private void authorize(String token) {
        authorizer.authorize(token, properties.getToken());
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
