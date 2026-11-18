package com.threecats.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.Collections;
import java.util.Map;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        String message,
        @JsonProperty("correlation_id")
        String correlationId,
        boolean retryable,
        Map<String, Object> details
) {
    public ErrorResponse {
        if (details == null) {
            details = Collections.emptyMap();
        }
    }

    public static ErrorResponse of(String code, String message, String correlationId, boolean retryable, Map<String, Object> details) {
        return new ErrorResponse(code, message, correlationId, retryable, details != null ? details : Collections.emptyMap());
    }

    public static ErrorResponse of(String code, String message, String correlationId, boolean retryable) {
        return new ErrorResponse(code, message, correlationId, retryable, Collections.emptyMap());
    }
}
