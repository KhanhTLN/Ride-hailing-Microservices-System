package com.threecats.userservice.common.exception.resource;

import com.threecats.userservice.common.exception.BaseException;

public abstract class ResourceInvalidStateException extends BaseException {
    public ResourceInvalidStateException() {
        super(400, "Resource Invalid State");
    }

    public ResourceInvalidStateException(String message) {
        super(400, message);
    }
}
