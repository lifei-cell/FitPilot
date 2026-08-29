package com.fitpilot.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class SecureTokenMatcher {
    private SecureTokenMatcher() {}

    public static boolean matches(String expected, String candidate) {
        return expected != null && candidate != null && !expected.isBlank() && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
    }
}
