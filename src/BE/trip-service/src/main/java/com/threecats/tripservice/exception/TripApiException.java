package com.threecats.tripservice.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Map;

@Getter
public class TripApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final boolean retryable;
    private final Map<String, Object> details;

    public TripApiException(HttpStatus status, String code, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
        this.details = Map.of();
    }

    public TripApiException(HttpStatus status, String code, String message) {
        this(status, code, message, false);
    }
}
