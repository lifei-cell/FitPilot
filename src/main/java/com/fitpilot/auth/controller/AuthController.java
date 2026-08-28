package com.fitpilot.auth.controller;

import com.fitpilot.auth.application.AuthService;
import com.fitpilot.auth.dto.AuthDtos;
import com.fitpilot.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService service;
    public AuthController(AuthService service) { this.service = service; }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<AuthDtos.UserView> register(@Valid @RequestBody AuthDtos.RegisterRequest request) {
        return ApiResponse.success(service.register(request));
    }

    @PostMapping("/login")
    ApiResponse<AuthDtos.LoginView> login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        return ApiResponse.success(service.login(request));
    }
}
