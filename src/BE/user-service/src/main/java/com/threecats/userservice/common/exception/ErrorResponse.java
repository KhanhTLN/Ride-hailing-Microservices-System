package com.threecats.userservice.common.exception;

public record ErrorResponse(
        int status,
        String message
) {
}
