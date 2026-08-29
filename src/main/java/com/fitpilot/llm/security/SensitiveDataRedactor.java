package com.fitpilot.llm.security;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class SensitiveDataRedactor {
    private static final Pattern EMAIL = Pattern.compile("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[a-z0-9._~-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern JWT = Pattern.compile("eyJ[a-zA-Z0-9_-]+\\.eyJ[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+");
    private static final Pattern PASSWORD = Pattern.compile("(?i)(password|passwd|密码)\\s*[:=]\\s*[^\\s,;]+", Pattern.CASE_INSENSITIVE);
    public String redact(String value) {
        if (value == null) return "";
        String result = EMAIL.matcher(value).replaceAll("[REDACTED_EMAIL]");
        result = BEARER.matcher(result).replaceAll("Bearer [REDACTED]");
        result = JWT.matcher(result).replaceAll("[REDACTED_JWT]");
        return PASSWORD.matcher(result).replaceAll("$1=[REDACTED]");
    }
}
