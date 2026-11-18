package com.threecats.common.exception.resource;

import com.threecats.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends BaseException {

    public ResourceNotFoundException() {
        super(HttpStatus.NOT_FOUND.value(), "NOT_FOUND", "Resource not found");
    }

    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND.value(), "NOT_FOUND", message);
    }

    public ResourceNotFoundException(String fieldName, Object fieldValue) {
        super(HttpStatus.NOT_FOUND.value(), "NOT_FOUND", String.format("Resource not found with %s: '%s'", fieldName, fieldValue));
    }
}
