package com.threecats.userservice.common.exception;

public abstract class BaseException extends RuntimeException {
    private final int statusCode;

    protected BaseException(final int statusCode, final String message) {
        super(message);
        this.statusCode = statusCode;
    }

}
