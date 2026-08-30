package com.fitpilot.auth.controller;

import com.fitpilot.auth.application.AuthService;
import com.fitpilot.auth.dto.AuthDtos;
import com.fitpilot.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService service;
    private final boolean secureCookie;
    public AuthController(AuthService service,
                          @Value("${security.jwt.refresh-cookie-secure:true}") boolean secureCookie) {
        this.service = service;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<AuthDtos.UserView> register(@Valid @RequestBody AuthDtos.RegisterRequest request) {
        return ApiResponse.success(service.register(request));
    }

    @PostMapping("/login")
    ApiResponse<AuthDtos.LoginView> login(@Valid @RequestBody AuthDtos.LoginRequest request,
                                          HttpServletResponse response) {
        AuthService.Session session = service.login(request);
        setCookie(response, session.refreshToken().value(), session.refreshToken().maxAgeSeconds());
        return ApiResponse.success(session.view());
    }

    @PostMapping("/refresh")
    ApiResponse<AuthDtos.LoginView> refresh(
            @CookieValue(name = "fitpilot_refresh", required = false) String refreshToken,
            HttpServletResponse response) {
        AuthService.Session session = service.refresh(refreshToken);
        setCookie(response, session.refreshToken().value(), session.refreshToken().maxAgeSeconds());
        return ApiResponse.success(session.view());
    }

    @PostMapping("/logout")
    ApiResponse<Void> logout(@CookieValue(name = "fitpilot_refresh", required = false) String refreshToken,
                             HttpServletResponse response) {
        service.logout(refreshToken);
        setCookie(response, "", 0);
        return ApiResponse.success();
    }

    private void setCookie(HttpServletResponse response, String value, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from("fitpilot_refresh", value)
                .httpOnly(true).secure(secureCookie).sameSite("Strict")
                .path("/api/v1/auth").maxAge(maxAgeSeconds).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
