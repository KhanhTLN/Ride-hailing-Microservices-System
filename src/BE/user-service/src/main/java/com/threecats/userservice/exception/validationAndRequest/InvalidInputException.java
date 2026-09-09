package com.threecats.userservice.exception.validationAndRequest;

import com.threecats.userservice.exception.BaseException;

public abstract class InvalidInputException extends BaseException {
    public InvalidInputException() { super(400, "Invalid Input"); }
    public InvalidInputException(String message) { super(400, message); }
}
