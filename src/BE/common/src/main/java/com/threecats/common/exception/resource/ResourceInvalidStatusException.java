package com.threecats.common.exception.resource;

import com.threecats.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class ResourceInvalidStatusException extends BaseException {

    public ResourceInvalidStatusException() {
        super(HttpStatus.CONFLICT.value(), "INVALID_TRANSITION", "Invalid resource status transition");
    }

    public ResourceInvalidStatusException(String message) {
        super(HttpStatus.CONFLICT.value(), "INVALID_TRANSITION", message);
    }
}
