package com.fitpilot.infrastructure.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.common.response.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;

@Component
public class IdempotencyFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final DefaultRedisScript<String> ACQUIRE = new DefaultRedisScript<>(
            "local v=redis.call('GET',KEYS[1]); if v then return v end; " +
                    "redis.call('PSETEX',KEYS[1],ARGV[1],'P'); return 'A'", String.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final PerformanceProperties properties;
    private final RedisFailureGuard failures = new RedisFailureGuard();

    public IdempotencyFilter(StringRedisTemplate redis, ObjectMapper objectMapper,
                             PerformanceProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.idempotency().enabled() || !MUTATING.contains(request.getMethod())
                || request.getHeader("Idempotency-Key") == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestKey = request.getHeader("Idempotency-Key").trim();
        if (requestKey.isEmpty() || requestKey.length() > 128) {
            writeError(response, 400, ErrorCode.VALIDATION_ERROR, "Idempotency-Key must contain 1-128 characters");
            return;
        }
        String redisKey = redisKey(request, requestKey);
        String state = acquire(redisKey);
        if (state == null) {
            chain.doFilter(request, response);
            return;
        }
        if (state.startsWith("{")) {
            replay(response, objectMapper.readValue(state, StoredResponse.class));
            return;
        }
        if ("P".equals(state)) {
            writeError(response, 409, ErrorCode.IDEMPOTENCY_IN_PROGRESS, "request with this key is still processing");
            return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapped);
            if (wrapped.getStatus() >= 200 && wrapped.getStatus() < 300) {
                StoredResponse stored = new StoredResponse(wrapped.getStatus(), wrapped.getContentType(),
                        Base64.getEncoder().encodeToString(wrapped.getContentAsByteArray()));
                try {
                    redis.opsForValue().set(redisKey, objectMapper.writeValueAsString(stored),
                            Duration.ofSeconds(properties.idempotency().resultTtlSeconds()));
                } catch (RuntimeException ex) { failures.warn(log, "idempotency-store", ex); }
            } else deleteQuietly(redisKey);
        } catch (RuntimeException ex) {
            deleteQuietly(redisKey);
            throw ex;
        } finally {
            wrapped.copyBodyToResponse();
        }
    }

    private String acquire(String key) {
        try {
            return redis.execute(ACQUIRE, List.of(key),
                    String.valueOf(properties.idempotency().processingTtlSeconds() * 1000));
        } catch (RuntimeException ex) {
            failures.warn(log, "idempotency", ex);
            return null;
        }
    }

    private void replay(HttpServletResponse response, StoredResponse stored) throws IOException {
        response.setStatus(stored.status());
        response.setContentType(stored.contentType());
        response.setHeader("Idempotency-Replayed", "true");
        response.getOutputStream().write(Base64.getDecoder().decode(stored.body()));
    }

    private void writeError(HttpServletResponse response, int status, ErrorCode code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(code.code(), message));
    }

    private void deleteQuietly(String key) {
        try { redis.delete(key); }
        catch (RuntimeException ex) { failures.warn(log, "idempotency-delete", ex); }
    }

    private String redisKey(HttpServletRequest request, String requestKey) {
        String authorization = request.getHeader("Authorization");
        String scope = request.getMethod() + "|" + request.getRequestURI() + "|"
                + (authorization == null ? request.getRemoteAddr() : authorization) + "|" + requestKey;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8));
            return "fitpilot:v1:idempotency:" + java.util.HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private record StoredResponse(int status, String contentType, String body) {}
}
