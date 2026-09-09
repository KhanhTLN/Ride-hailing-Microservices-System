package com.threecats.userservice.common.exception.validationAndRequest;

import com.threecats.userservice.common.exception.BaseException;

public abstract class InvalidInputException extends BaseException {
    public InvalidInputException() { super(400, "Invalid Input"); }
    public InvalidInputException(String message) { super(400, message); }
}
