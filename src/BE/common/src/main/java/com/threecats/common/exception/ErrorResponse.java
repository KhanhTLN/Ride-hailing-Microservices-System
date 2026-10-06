package com.threecats.common.exception;

public record ErrorResponse(
        int status,
        String message
) {
}
