package com.fitpilot.auth.application;

import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

@Service
public class RefreshTokenService {
    private static final String KEY_PREFIX = "fitpilot:auth:refresh:";
    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();
    private final Duration ttl;

    public RefreshTokenService(StringRedisTemplate redis,
                               @Value("${security.jwt.refresh-expiration-seconds:2592000}") long ttlSeconds) {
        this.redis = redis;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public IssuedToken issue(long userId, String username) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(key(token), userId + ":" + username, ttl);
        return new IssuedToken(token, ttl.toSeconds());
    }

    public RotatedToken rotate(String token) {
        if (token == null || token.isBlank()) throw invalid();
        String subject = redis.opsForValue().getAndDelete(key(token));
        if (subject == null) throw invalid();
        int separator = subject.indexOf(':');
        if (separator <= 0 || separator == subject.length() - 1) throw invalid();
        long userId;
        try {
            userId = Long.parseLong(subject.substring(0, separator));
        } catch (NumberFormatException ex) {
            throw invalid();
        }
        String username = subject.substring(separator + 1);
        return new RotatedToken(userId, username, issue(userId, username));
    }

    public void revoke(String token) {
        if (token != null && !token.isBlank()) redis.delete(key(token));
    }

    private String key(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private BusinessException invalid() {
        return new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED, "refresh session is invalid or expired",
                HttpStatus.UNAUTHORIZED);
    }

    public record IssuedToken(String value, long maxAgeSeconds) {}
    public record RotatedToken(long userId, String username, IssuedToken token) {}
}
