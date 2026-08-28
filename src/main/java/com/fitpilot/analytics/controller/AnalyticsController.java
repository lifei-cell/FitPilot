package com.fitpilot.analytics.controller;

import com.fitpilot.analytics.application.AnalyticsService;
import com.fitpilot.analytics.dto.AnalyticsDtos;
import com.fitpilot.common.response.ApiResponse;
import com.fitpilot.common.security.CurrentUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {
    private final AnalyticsService service;
    public AnalyticsController(AnalyticsService service) { this.service = service; }

    @GetMapping("/overview")
    ApiResponse<AnalyticsDtos.Overview> overview(Authentication auth) {
        return ApiResponse.success(service.overview(CurrentUser.id(auth)));
    }

    @GetMapping("/exercises/{exerciseId}/progress")
    ApiResponse<List<AnalyticsDtos.ExerciseProgress>> progress(@PathVariable long exerciseId, Authentication auth) {
        return ApiResponse.success(service.exerciseProgress(CurrentUser.id(auth), exerciseId));
    }

    @GetMapping("/body-weight")
    ApiResponse<List<AnalyticsDtos.BodyWeightPoint>> bodyWeight(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Authentication auth) {
        return ApiResponse.success(service.bodyWeightTrend(CurrentUser.id(auth), startDate, endDate));
    }
}
