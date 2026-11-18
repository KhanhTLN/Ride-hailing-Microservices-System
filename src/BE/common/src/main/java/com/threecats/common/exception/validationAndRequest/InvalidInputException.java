package com.threecats.common.exception.validationAndRequest;

import com.threecats.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class InvalidInputException extends BaseException {

    public InvalidInputException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY.value(), "INVALID_ARGUMENT", "Invalid input");
    }

    public InvalidInputException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY.value(), "INVALID_ARGUMENT", message);
    }
}
