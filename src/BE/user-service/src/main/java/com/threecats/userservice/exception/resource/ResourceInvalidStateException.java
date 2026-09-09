package com.threecats.userservice.exception.resource;

import com.threecats.userservice.exception.BaseException;

public abstract class ResourceInvalidStateException extends BaseException {
    public ResourceInvalidStateException() {
        super(400, "Resource Invalid State");
    }

    public ResourceInvalidStateException(String message) {
        super(400, message);
    }
}
