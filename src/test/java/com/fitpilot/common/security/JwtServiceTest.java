package com.fitpilot.common.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {
    private static final String OLD_SECRET = "old-jwt-secret-with-at-least-32-bytes-for-rotation";
    private static final String NEW_SECRET = "new-jwt-secret-with-at-least-32-bytes-for-rotation";

    @Test
    void acceptsTokensFromPreviousKeyDuringRotationWindow() {
        String oldToken = service(OLD_SECRET, "").issue(7, "before-rotation");
        JwtService rotating = service(NEW_SECRET, OLD_SECRET);

        assertEquals("7", rotating.parse(oldToken).getSubject());
        assertEquals("before-rotation", rotating.parse(oldToken).get("username", String.class));
    }

    @Test
    void signsWithNewKeyAndRejectsOldKeyAfterRevocation() {
        JwtService rotating = service(NEW_SECRET, OLD_SECRET);
        String newToken = rotating.issue(8, "after-rotation");
        String oldToken = service(OLD_SECRET, "").issue(7, "before-rotation");

        assertEquals("8", service(NEW_SECRET, "").parse(newToken).getSubject());
        assertThrows(JwtException.class, () -> service(NEW_SECRET, "").parse(oldToken));
    }

    private JwtService service(String secret, String previousSecret) {
        return new JwtService(secret, previousSecret, 300);
    }
}
