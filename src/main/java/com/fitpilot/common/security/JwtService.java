package com.fitpilot.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
public class JwtService {
    private final SecretKey signingKey;
    private final List<SecretKey> verificationKeys;
    private final long expirationSeconds;

    public JwtService(@Value("${security.jwt.secret}") String secret,
                      @Value("${security.jwt.previous-secret:}") String previousSecret,
                      @Value("${security.jwt.expiration-seconds}") long expirationSeconds) {
        this.signingKey = key(secret, "JWT_SECRET");
        List<SecretKey> keys = new ArrayList<>();
        keys.add(signingKey);
        if (previousSecret != null && !previousSecret.isBlank() && !previousSecret.equals(secret)) {
            keys.add(key(previousSecret, "JWT_PREVIOUS_SECRET"));
        }
        this.verificationKeys = List.copyOf(keys);
        this.expirationSeconds = expirationSeconds;
    }

    public String issue(long userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder().subject(Long.toString(userId)).claim("username", username)
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .signWith(signingKey).compact();
    }

    public Claims parse(String token) {
        JwtException lastFailure = null;
        for (SecretKey verificationKey : verificationKeys) {
            try {
                return Jwts.parser().verifyWith(verificationKey).build()
                        .parseSignedClaims(token).getPayload();
            } catch (JwtException exception) {
                lastFailure = exception;
            }
        }
        throw lastFailure;
    }

    public long expirationSeconds() { return expirationSeconds; }

    private static SecretKey key(String value, String name) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException(name + " must contain at least 32 bytes");
        }
        return Keys.hmacShaKeyFor(value.getBytes(StandardCharsets.UTF_8));
    }
}
