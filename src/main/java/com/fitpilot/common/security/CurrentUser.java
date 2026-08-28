package com.fitpilot.common.security;

import org.springframework.security.core.Authentication;

public final class CurrentUser {
    private CurrentUser() {}

    public static long id(Authentication authentication) {
        return (Long) authentication.getPrincipal();
    }
}
