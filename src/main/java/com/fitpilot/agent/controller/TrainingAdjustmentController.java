package com.fitpilot.agent.controller;

import com.fitpilot.agent.adjustment.TrainingAdjustmentDtos;
import com.fitpilot.agent.adjustment.TrainingAdjustmentService;
import com.fitpilot.common.response.ApiResponse;
import com.fitpilot.common.response.PageResult;
import com.fitpilot.common.security.CurrentUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/agent/plan-adjustments")
public class TrainingAdjustmentController {
    private final TrainingAdjustmentService service;

    public TrainingAdjustmentController(TrainingAdjustmentService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<PageResult<TrainingAdjustmentDtos.AdjustmentView>> list(
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size, Authentication auth) {
        return ApiResponse.success(service.list(CurrentUser.id(auth), page, size));
    }

    @PostMapping("/{id}/reject")
    ApiResponse<Void> reject(@PathVariable UUID id, Authentication auth) {
        service.reject(CurrentUser.id(auth), id);
        return ApiResponse.success();
    }

    @GetMapping("/{id}")
    ApiResponse<TrainingAdjustmentDtos.AdjustmentView> get(@PathVariable UUID id, Authentication auth) {
        return ApiResponse.success(service.get(CurrentUser.id(auth), id));
    }
}
