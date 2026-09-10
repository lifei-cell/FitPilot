package com.fitpilot.llm.infrastructure;

final class LlmProviderException extends RuntimeException {
    private final Integer httpStatus;
    private final boolean retryable;
    private final String code;
    private final long retryAfterMs;

    LlmProviderException(Integer status, boolean retryable, String code, long retryAfterMs) {
        this(status, retryable, code, retryAfterMs, null);
    }

    LlmProviderException(Integer status, boolean retryable, String code,
                         long retryAfterMs, Throwable cause) {
        super(code, cause);
        this.httpStatus = status;
        this.retryable = retryable;
        this.code = code;
        this.retryAfterMs = retryAfterMs;
    }

    Integer httpStatus() { return httpStatus; }
    boolean retryable() { return retryable; }
    String code() { return code; }
    long retryAfterMs() { return retryAfterMs; }
}
