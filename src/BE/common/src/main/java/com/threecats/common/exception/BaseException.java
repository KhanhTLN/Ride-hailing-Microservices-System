package com.threecats.common.exception;

import java.util.Collections;
import java.util.Map;

public abstract class BaseException extends RuntimeException {
    private final int statusCode;
    private final String errorCode;
    private final boolean retryable;
    private final Map<String, Object> details;

    protected BaseException(final int statusCode, final String message) {
        this(statusCode, "INTERNAL", message, false, Collections.emptyMap());
    }

    protected BaseException(final int statusCode, final String errorCode, final String message) {
        this(statusCode, errorCode, message, false, Collections.emptyMap());
    }

    protected BaseException(final int statusCode, final String errorCode, final String message, final boolean retryable, final Map<String, Object> details) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.details = details != null ? details : Collections.emptyMap();
    }

    public int getStatus() {
        return this.statusCode;
    }

    public String getErrorCode() {
        return this.errorCode;
    }

    public boolean isRetryable() {
        return this.retryable;
    }

    public Map<String, Object> getDetails() {
        return this.details;
    }
}
