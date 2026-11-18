package com.threecats.common.exception.resource;

import com.threecats.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class ResourceAlreadyExistException extends BaseException {

    public ResourceAlreadyExistException() {
        super(HttpStatus.CONFLICT.value(), "IDEMPOTENCY_CONFLICT", "Resource already exists");
    }

    public ResourceAlreadyExistException(String message) {
        super(HttpStatus.CONFLICT.value(), "IDEMPOTENCY_CONFLICT", message);
    }
}
