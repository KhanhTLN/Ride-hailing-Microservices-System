package com.threecats.tripservice.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class TripExceptionHandler {

    @ExceptionHandler({org.springframework.web.bind.MissingRequestHeaderException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String,Object>> invalidRequest(Exception ex) {
        return ResponseEntity.status(422).body(Map.of("code","INVALID_ARGUMENT","message","Invalid request headers or body","correlation_id",com.threecats.common.context.CorrelationContext.get(),"retryable",false,"details",Map.of()));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Map<String,Object>> forbidden(org.springframework.security.access.AccessDeniedException ex) {
        return ResponseEntity.status(403).body(Map.of("code","FORBIDDEN","message","Caller is not authorized","correlation_id",com.threecats.common.context.CorrelationContext.get(),"retryable",false,"details",Map.of()));
    }

    @ExceptionHandler(TripApiException.class)
    public ResponseEntity<Map<String, Object>> handleTripApiException(TripApiException ex, HttpServletRequest request) {
        String correlationId = request.getHeader("X-Correlation-ID");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        Map<String, Object> body = new HashMap<>();
        body.put("code", ex.getCode());
        body.put("message", ex.getMessage());
        body.put("correlation_id", correlationId);
        body.put("retryable", ex.isRetryable());
        body.put("details", ex.getDetails());

        return ResponseEntity.status(ex.getStatus())
                .header("X-Correlation-ID", correlationId)
                .body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String correlationId = request.getHeader("X-Correlation-ID");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        Map<String, Object> details = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                details.put(error.getField(), error.getDefaultMessage())
        );

        Map<String, Object> body = new HashMap<>();
        body.put("code", "VALIDATION_FAILED");
        body.put("message", "Request parameters or body validation failed");
        body.put("correlation_id", correlationId);
        body.put("retryable", false);
        body.put("details", details);

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .header("X-Correlation-ID", correlationId)
                .body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneralException(Exception ex, HttpServletRequest request) {
        String correlationId = request.getHeader("X-Correlation-ID");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        Map<String, Object> body = new HashMap<>();
        body.put("code", "INTERNAL_ERROR");
        body.put("message", "Unexpected internal error");
        body.put("correlation_id", correlationId);
        body.put("retryable", true);
        body.put("details", Map.of());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header("X-Correlation-ID", correlationId)
                .body(body);
    }
}
