package com.fitpilot.agent.controller;

import com.fitpilot.agent.dto.AgentDtos;
import com.fitpilot.agent.infrastructure.AgentRepository;
import com.fitpilot.agent.product.AgentProductMetricsDtos;
import com.fitpilot.agent.product.AgentProductMetricsService;
import com.fitpilot.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/operations/agent")
public class AgentOperationsController {
    private final AgentRepository repository;
    private final AgentProductMetricsService productMetrics;

    public AgentOperationsController(AgentRepository repository, AgentProductMetricsService productMetrics) {
        this.repository = repository;
        this.productMetrics = productMetrics;
    }

    @GetMapping("/metrics")
    AgentDtos.EvaluationMetrics metrics() {
        return repository.metrics();
    }

    @GetMapping("/product-metrics")
    ApiResponse<AgentProductMetricsDtos.Snapshot> productMetrics(
            @RequestParam(defaultValue = "28") @Min(14) @Max(365) int windowDays,
            @RequestParam(defaultValue = "28") @Min(7) @Max(90) int outcomeWindowDays) {
        return ApiResponse.success(productMetrics.snapshot(windowDays, outcomeWindowDays));
    }

    @PutMapping("/executions/{id}/expected-tools")
    ApiResponse<Void> label(@PathVariable UUID id, @Valid @RequestBody AgentDtos.EvaluationLabel label) {
        repository.label(id, label.expectedTools());
        return ApiResponse.success();
    }
}
