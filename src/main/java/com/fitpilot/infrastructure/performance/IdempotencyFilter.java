package com.fitpilot.infrastructure.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.common.idempotency.IdempotencyRequest;
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
                    "redis.call('PSETEX',KEYS[1],ARGV[1],'P:'..ARGV[2]); return 'A'", String.class);
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

        byte[] requestBody = request.getInputStream().readAllBytes();
        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request, requestBody);
        IdempotencyFingerprint.Scope scope = IdempotencyFingerprint.create(cachedRequest, requestKey, requestBody);
        cachedRequest.setAttribute(IdempotencyRequest.ATTRIBUTE,
                new IdempotencyRequest(requestKey, scope.fingerprint()));

        String state = acquire(scope.redisKey(), scope.fingerprint());
        if (state == null) {
            chain.doFilter(cachedRequest, response);
            return;
        }
        if (state.startsWith("{")) {
            StoredResponse stored = objectMapper.readValue(state, StoredResponse.class);
            if (!scope.fingerprint().equals(stored.fingerprint())) {
                reused(response);
                return;
            }
            replay(response, stored);
            return;
        }
        if (state.startsWith("P:")) {
            if (!state.substring(2).equals(scope.fingerprint())) {
                reused(response);
                return;
            }
            writeError(response, 409, ErrorCode.IDEMPOTENCY_IN_PROGRESS, "request with this key is still processing");
            return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(cachedRequest, wrapped);
            if (wrapped.getStatus() >= 200 && wrapped.getStatus() < 300) {
                storeResponse(scope, wrapped);
            } else deleteQuietly(scope.redisKey());
        } catch (IOException | ServletException | RuntimeException ex) {
            deleteQuietly(scope.redisKey());
            throw ex;
        } finally {
            wrapped.copyBodyToResponse();
        }
    }

    private String acquire(String key, String fingerprint) {
        try {
            return redis.execute(ACQUIRE, List.of(key),
                    String.valueOf(properties.idempotency().processingTtlSeconds() * 1000), fingerprint);
        } catch (RuntimeException ex) {
            failures.warn(log, "idempotency", ex);
            return null;
        }
    }

    private void storeResponse(IdempotencyFingerprint.Scope scope, ContentCachingResponseWrapper response) {
        byte[] body = response.getContentAsByteArray();
        if (body.length > properties.idempotency().maxCachedResponseBytes()) {
            log.warn("operation=idempotency-store result=skipped reason=response-too-large bytes={} limit={}",
                    body.length, properties.idempotency().maxCachedResponseBytes());
            deleteQuietly(scope.redisKey());
            return;
        }
        StoredResponse stored = new StoredResponse(scope.fingerprint(), response.getStatus(),
                response.getContentType(), Base64.getEncoder().encodeToString(body));
        String serialized;
        try {
            serialized = objectMapper.writeValueAsString(stored);
        } catch (JsonProcessingException exception) {
            log.error("operation=idempotency-store result=skipped reason=serialization-failed", exception);
            deleteQuietly(scope.redisKey());
            return;
        }
        try {
            redis.opsForValue().set(scope.redisKey(), serialized,
                    Duration.ofSeconds(properties.idempotency().resultTtlSeconds()));
        } catch (RuntimeException ex) {
            failures.warn(log, "idempotency-store", ex);
            deleteQuietly(scope.redisKey());
        }
    }

    private void replay(HttpServletResponse response, StoredResponse stored) throws IOException {
        response.setStatus(stored.status());
        if (stored.contentType() != null) response.setContentType(stored.contentType());
        response.setHeader("Idempotency-Replayed", "true");
        response.getOutputStream().write(Base64.getDecoder().decode(stored.body()));
    }

    private void reused(HttpServletResponse response) throws IOException {
        writeError(response, 409, ErrorCode.IDEMPOTENCY_KEY_REUSED,
                "Idempotency-Key was already used with different request parameters or body");
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

    private record StoredResponse(String fingerprint, int status, String contentType, String body) {}
}
