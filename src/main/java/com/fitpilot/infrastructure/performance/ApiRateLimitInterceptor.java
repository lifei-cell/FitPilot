package com.fitpilot.infrastructure.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

@Component
public class ApiRateLimitInterceptor implements HandlerInterceptor {
    private final RedisTokenBucketRateLimiter limiter;
    private final PerformanceProperties properties;
    private final ObjectMapper objectMapper;

    public ApiRateLimitInterceptor(RedisTokenBucketRateLimiter limiter, PerformanceProperties properties,
                                   ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!properties.rateLimit().enabled()) return true;
        boolean login = request.getRequestURI().startsWith("/api/v1/auth/");
        long capacity = login ? properties.rateLimit().loginCapacity() : properties.rateLimit().apiCapacity();
        long refill = login ? properties.rateLimit().loginRefillPerSecond()
                : properties.rateLimit().apiRefillPerSecond();
        String identity = login ? "ip:" + clientIp(request) : identity(request);
        String route = login ? "auth" : "api";
        var result = limiter.consume(route + ":" + identity, capacity, refill);
        response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remaining()));
        if (result.degraded()) response.setHeader("X-RateLimit-Degraded", "local");
        if (result.allowed()) return true;
        response.setStatus(429);
        response.setHeader("Retry-After", "1");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.error(ErrorCode.RATE_LIMITED.code(), "rate limit exceeded"));
        return false;
    }

    private String identity(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof Long id) {
            return "user:" + id;
        }
        return "ip:" + clientIp(request);
    }

    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
