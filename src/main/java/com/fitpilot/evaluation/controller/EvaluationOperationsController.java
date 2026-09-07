package com.fitpilot.evaluation.controller;

import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.common.response.ApiResponse;
import com.fitpilot.evaluation.application.EvaluationService;
import com.fitpilot.evaluation.dto.EvaluationDtos;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/operations/evaluations")
public class EvaluationOperationsController {
    private final EvaluationService service;

    public EvaluationOperationsController(EvaluationService service) {
        this.service = service;
    }

    @PostMapping("/agent/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<Map<String, UUID>> agent(
            @Valid @RequestBody(required = false) EvaluationDtos.AgentRunRequest request) {
        return ApiResponse.success(Map.of("runId", service.startAgent(request == null ? null : request.mode())));
    }

    @PostMapping("/rag/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<Map<String, UUID>> rag() {
        return ApiResponse.success(Map.of("runId", service.startRag()));
    }

    @GetMapping("/runs/{id}")
    ApiResponse<EvaluationDtos.RunView> get(@PathVariable UUID id) {
        var run = service.find(id).orElseThrow(() -> new BusinessException(
                ErrorCode.EVALUATION_RUN_NOT_FOUND, "evaluation run not found", HttpStatus.NOT_FOUND));
        return ApiResponse.success(run);
    }
}
