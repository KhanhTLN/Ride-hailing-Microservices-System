package com.threecats.common.exception;

import com.threecats.common.context.CorrelationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(CorrelationContext.CORRELATION_HEADER, CorrelationContext.get());
        return headers;
    }

    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ErrorResponse> handleBaseException(BaseException ex) {
        log.warn("Business exception: code={}, message={}", ex.getErrorCode(), ex.getMessage());
        ErrorResponse response = ErrorResponse.of(
                ex.getErrorCode(),
                ex.getMessage(),
                CorrelationContext.get(),
                ex.isRetryable(),
                ex.getDetails()
        );

        return ResponseEntity
                .status(ex.getStatus())
                .headers(createHeaders())
                .body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
        Map<String, Object> details = new HashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            details.put(fieldError.getField(), fieldError.getDefaultMessage());
        }

        String firstErrorMessage = ex.getBindingResult().getFieldErrors().isEmpty()
                ? "Invalid request payload"
                : ex.getBindingResult().getFieldErrors().get(0).getDefaultMessage();

        ErrorResponse response = ErrorResponse.of(
                "INVALID_ARGUMENT",
                firstErrorMessage,
                CorrelationContext.get(),
                false,
                details
        );

        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .headers(createHeaders())
                .body(response);
    }

    @ExceptionHandler({
            BadCredentialsException.class,
            InternalAuthenticationServiceException.class,
            UsernameNotFoundException.class
    })
    public ResponseEntity<ErrorResponse> handleAuthenticationException(Exception ex) {
        ErrorResponse response = ErrorResponse.of(
                "UNAUTHENTICATED",
                "Email hoặc mật khẩu không chính xác",
                CorrelationContext.get(),
                false
        );

        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .headers(createHeaders())
                .body(response);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(AccessDeniedException ex) {
        ErrorResponse response = ErrorResponse.of(
                "FORBIDDEN",
                "Không có quyền truy cập tài nguyên này",
                CorrelationContext.get(),
                false
        );

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .headers(createHeaders())
                .body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUncaughtException(Exception ex) {
        log.error("Uncaught exception occurred: ", ex);
        ErrorResponse response = ErrorResponse.of(
                "INTERNAL",
                "Đã có lỗi hệ thống xảy ra, vui lòng thử lại sau!",
                CorrelationContext.get(),
                false
        );

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .headers(createHeaders())
                .body(response);
    }
}
