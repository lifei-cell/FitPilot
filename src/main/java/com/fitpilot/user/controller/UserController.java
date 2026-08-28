package com.fitpilot.user.controller;

import com.fitpilot.common.response.ApiResponse;
import com.fitpilot.common.response.PageResult;
import com.fitpilot.common.security.CurrentUser;
import com.fitpilot.user.application.UserService;
import com.fitpilot.user.dto.UserDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@Validated
@RestController
@RequestMapping("/api/v1/users/me")
public class UserController {
    private final UserService service;
    public UserController(UserService service) { this.service = service; }

    @GetMapping
    ApiResponse<UserDtos.UserProfileView> me(Authentication auth) {
        return ApiResponse.success(service.getProfile(CurrentUser.id(auth)));
    }

    @PutMapping("/profile")
    ApiResponse<UserDtos.UserProfileView> update(@Valid @RequestBody UserDtos.ProfileUpdateRequest request, Authentication auth) {
        return ApiResponse.success(service.updateProfile(CurrentUser.id(auth), request));
    }

    @PostMapping("/body-metrics")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<UserDtos.BodyMetricView> addMetric(@Valid @RequestBody UserDtos.BodyMetricRequest request, Authentication auth) {
        return ApiResponse.success(service.addMetric(CurrentUser.id(auth), request));
    }

    @GetMapping("/body-metrics")
    ApiResponse<PageResult<UserDtos.BodyMetricView>> listMetrics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size,
            Authentication auth) {
        return ApiResponse.success(service.listMetrics(CurrentUser.id(auth), startDate, endDate, page, size));
    }

    @GetMapping("/body-metrics/latest")
    ApiResponse<UserDtos.BodyMetricView> latestMetric(Authentication auth) {
        return ApiResponse.success(service.latestMetric(CurrentUser.id(auth)));
    }
}
