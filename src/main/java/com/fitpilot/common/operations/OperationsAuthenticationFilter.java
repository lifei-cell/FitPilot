package com.fitpilot.common.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.common.response.ApiResponse;
import com.fitpilot.common.security.SecureTokenMatcher;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class OperationsAuthenticationFilter extends OncePerRequestFilter {
    public static final String ROLE = "ROLE_OPERATIONS";
    private static final String TOKEN_HEADER = "X-Operations-Token";
    private static final AntPathRequestMatcher OPERATIONS = new AntPathRequestMatcher("/api/v1/operations/**");

    private final OperationsProperties properties;
    private final ObjectMapper objectMapper;

    public OperationsAuthenticationFilter(OperationsProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !OPERATIONS.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String configured = properties.getToken();
        String candidate = request.getHeader(TOKEN_HEADER);
        if (configured == null || configured.isBlank() || !SecureTokenMatcher.matches(configured, candidate)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(),
                    ApiResponse.error(ErrorCode.ACCESS_DENIED.code(), "invalid operations token"));
            return;
        }

        var authentication = new UsernamePasswordAuthenticationToken(
                "fitpilot-operations", null, List.of(new SimpleGrantedAuthority(ROLE)));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }
}
