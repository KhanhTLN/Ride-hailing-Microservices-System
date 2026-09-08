package com.threecats.userservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        int status,
        T Data,
        String message,
        String errorCode,
        Instant timestamp
) {

    // SUCCESS RESPONSE
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, 200, data, null, null, Instant.now());
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(true, 200, data, message, null, Instant.now());
    }

    public static <T> ApiResponse<T> success(String message) {
        return new ApiResponse<>(true, 200, null, message, null, Instant.now());
    }

    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(true, 201, data, null, null, Instant.now());
    }


    // ERROR RESPONSE
    public static <T> ApiResponse<T> error(int status, String message, String errorCode){
        return new ApiResponse<>(false, status, null, message, errorCode, Instant.now());
    }
    public static <T> ApiResponse<T> error(int status, String message) {
        return new ApiResponse<>(false, status, null, message, null, Instant.now());
    }
}
