package com.fitpilot.common.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class OperationsAuthenticationFilterTest {
    private final OperationsProperties properties = new OperationsProperties();
    private final OperationsAuthenticationFilter filter =
            new OperationsAuthenticationFilter(properties, new ObjectMapper());

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsAnyFutureOperationsRouteWithoutToken() throws Exception {
        properties.setToken("secret-token");
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/api/v1/operations/future/new-endpoint");
        request.setServletPath("/api/v1/operations/future/new-endpoint");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":1002");
        verifyNoInteractions(chain);
    }

    @Test
    void grantsOperationsRoleOnlyForMatchingToken() throws Exception {
        properties.setToken("secret-token");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/operations/status");
        request.setServletPath("/api/v1/operations/status");
        request.addHeader("X-Operations-Token", "secret-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority").containsExactly("ROLE_OPERATIONS");
    }
}
