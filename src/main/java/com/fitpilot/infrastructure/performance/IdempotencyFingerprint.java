package com.fitpilot.infrastructure.performance;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class IdempotencyFingerprint {
    private IdempotencyFingerprint() {}

    static Scope create(HttpServletRequest request, String requestKey, byte[] body) {
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        String path = canonicalPath(request.getRequestURI());
        String query = canonicalQuery(request.getQueryString());
        String bodyHash = sha256(body);
        String identity = identity(request);

        String routeScope = identity + "|" + method + "|" + path + "|" + requestKey;
        String requestScope = method + "|" + path + "|" + query + "|" + bodyHash;
        return new Scope("fitpilot:v2:idempotency:" + sha256(routeScope), sha256(requestScope));
    }

    static String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return "";
        List<QueryPart> parts = new ArrayList<>();
        for (String token : rawQuery.split("&", -1)) {
            int separator = token.indexOf('=');
            String name = separator < 0 ? token : token.substring(0, separator);
            String value = separator < 0 ? "" : token.substring(separator + 1);
            parts.add(new QueryPart(decode(name), decode(value)));
        }
        parts.sort(Comparator.comparing(QueryPart::name).thenComparing(QueryPart::value));
        return parts.stream().map(part -> encode(part.name()) + "=" + encode(part.value()))
                .reduce((left, right) -> left + "&" + right).orElse("");
    }

    private static String canonicalPath(String requestUri) {
        String path;
        try {
            path = URI.create(requestUri).normalize().getRawPath();
        } catch (IllegalArgumentException exception) {
            path = requestUri;
        }
        if (path == null || path.isBlank()) return "/";
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }

    private static String identity(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof Number principal) {
            return "user:" + principal.longValue();
        }
        return "anonymous:" + request.getRemoteAddr();
    }

    private static String decode(String value) {
        try { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
        catch (IllegalArgumentException exception) { return value; }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    record Scope(String redisKey, String fingerprint) {}
    private record QueryPart(String name, String value) {}
}
