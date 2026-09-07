package com.fitpilot.infrastructure.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import jakarta.servlet.http.HttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IdempotencyFilterTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotCacheResponsesAboveConfiguredLimit() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenReturn("A");
        PerformanceProperties properties = new PerformanceProperties(null, null,
                new PerformanceProperties.Idempotency(true, 60, 30, 4), null);
        IdempotencyFilter filter = new IdempotencyFilter(redis, new ObjectMapper(), properties);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(42L, null, List.of()));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/workouts");
        request.addHeader("Idempotency-Key", "key-1");
        request.setContentType("application/json");
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            ((HttpServletResponse) servletResponse).setStatus(201);
            servletResponse.getOutputStream().write("12345".getBytes(StandardCharsets.UTF_8));
        });

        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        verify(redis).delete(startsWith("fitpilot:v2:idempotency:"));
    }

    @Test
    void rejectsSameKeyWhenInProgressFingerprintDiffers() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn("P:different-fingerprint");
        PerformanceProperties properties = new PerformanceProperties(null, null,
                new PerformanceProperties.Idempotency(true, 60, 30, 1024), null);
        IdempotencyFilter filter = new IdempotencyFilter(redis, new ObjectMapper(), properties);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(42L, null, List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/workouts");
        request.addHeader("Idempotency-Key", "key-1");
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> chainCalled.set(true));

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString()).contains("\"code\":50004");
        assertThat(chainCalled).isFalse();
    }
}
