package com.threecats.userservice.exception;

import org.springframework.web.servlet.config.annotation.ResourceChainRegistration;

public class ResourceAlreadyExistException extends BaseException {
    public ResourceAlreadyExistException() {
        super(409, "Resource Already Exist");
    }

    public ResourceAlreadyExistException(String message) {
        super(409, message);
    }
}
