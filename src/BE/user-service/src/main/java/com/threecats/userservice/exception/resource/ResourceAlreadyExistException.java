package com.threecats.userservice.exception.resource;

import com.threecats.userservice.exception.BaseException;

public class ResourceAlreadyExistException extends BaseException {
    public ResourceAlreadyExistException() {
        super(409, "Resource Already Exist");
    }

    public ResourceAlreadyExistException(String message) {
        super(409, message);
    }
}
