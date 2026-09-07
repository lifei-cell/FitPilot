package com.fitpilot.infrastructure.performance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyFingerprintTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void canonicalizesPathAndQueryParameterOrder() {
        authenticate(42L);
        MockHttpServletRequest first = request("/api/v1/workouts/../workouts/", "z=2&a=hello%20world&a=1");
        MockHttpServletRequest second = request("/api/v1/workouts", "a=1&a=hello+world&z=2");

        var firstScope = IdempotencyFingerprint.create(first, "key-1", "{}".getBytes(StandardCharsets.UTF_8));
        var secondScope = IdempotencyFingerprint.create(second, "key-1", "{}".getBytes(StandardCharsets.UTF_8));

        assertThat(firstScope.redisKey()).isEqualTo(secondScope.redisKey());
        assertThat(firstScope.fingerprint()).isEqualTo(secondScope.fingerprint());
    }

    @Test
    void separatesUsersAndDetectsBodyChanges() {
        MockHttpServletRequest request = request("/api/v1/workouts", null);
        authenticate(42L);
        var original = IdempotencyFingerprint.create(request, "key-1", "{\"name\":\"A\"}".getBytes(StandardCharsets.UTF_8));
        var changed = IdempotencyFingerprint.create(request, "key-1", "{\"name\":\"B\"}".getBytes(StandardCharsets.UTF_8));

        authenticate(43L);
        var anotherUser = IdempotencyFingerprint.create(request, "key-1", "{\"name\":\"A\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(changed.redisKey()).isEqualTo(original.redisKey());
        assertThat(changed.fingerprint()).isNotEqualTo(original.fingerprint());
        assertThat(anotherUser.redisKey()).isNotEqualTo(original.redisKey());
    }

    private MockHttpServletRequest request(String uri, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setQueryString(query);
        return request;
    }

    private void authenticate(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }
}
