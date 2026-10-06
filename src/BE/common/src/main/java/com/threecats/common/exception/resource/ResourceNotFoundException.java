package com.threecats.common.exception.resource;

import com.threecats.common.exception.BaseException;

public class ResourceNotFoundException extends BaseException {

    public ResourceNotFoundException() {
        super(404, "Resource not found");
    }

    public ResourceNotFoundException(String message) {
        super(404, message);
    }

    public ResourceNotFoundException(String fieldName, Object fieldValue) {
        super(404, String.format("User not found with %s: '%s'", fieldName, fieldValue));
    }
}
