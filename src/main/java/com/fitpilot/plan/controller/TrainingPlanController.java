package com.fitpilot.plan.controller;

import com.fitpilot.common.response.ApiResponse;
import com.fitpilot.common.response.PageResult;
import com.fitpilot.common.security.CurrentUser;
import com.fitpilot.plan.application.TrainingPlanService;
import com.fitpilot.plan.dto.TrainingPlanDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/v1/training-plans")
public class TrainingPlanController {
    private final TrainingPlanService service;
    public TrainingPlanController(TrainingPlanService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<TrainingPlanDtos.PlanView> create(@Valid @RequestBody TrainingPlanDtos.CreateRequest request,
                                                   Authentication auth) {
        return ApiResponse.success(service.create(CurrentUser.id(auth), request));
    }

    @GetMapping
    ApiResponse<PageResult<TrainingPlanDtos.PlanSummary>> list(@RequestParam(required = false) String status,
                                                               @RequestParam(defaultValue = "1") @Min(1) long page,
                                                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size,
                                                               Authentication auth) {
        return ApiResponse.success(service.list(CurrentUser.id(auth), status, page, size));
    }

    @GetMapping("/{id}")
    ApiResponse<TrainingPlanDtos.PlanView> get(@PathVariable long id, Authentication auth) {
        return ApiResponse.success(service.get(CurrentUser.id(auth), id));
    }

    @PutMapping("/{id}")
    ApiResponse<TrainingPlanDtos.PlanView> update(@PathVariable long id,
                                                  @Valid @RequestBody TrainingPlanDtos.UpdateRequest request,
                                                  Authentication auth) {
        return ApiResponse.success(service.update(CurrentUser.id(auth), id, request));
    }

    @GetMapping("/active/current")
    ApiResponse<TrainingPlanDtos.PlanView> active(Authentication auth) {
        return ApiResponse.success(service.active(CurrentUser.id(auth)));
    }

    @PostMapping("/{id}/activate")
    ApiResponse<TrainingPlanDtos.PlanView> activate(@PathVariable long id, Authentication auth) {
        return ApiResponse.success(service.activate(CurrentUser.id(auth), id));
    }
}
