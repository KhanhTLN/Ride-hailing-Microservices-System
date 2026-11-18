package com.threecats.common.exception.authenAndAuthor;

import com.threecats.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class UnauthorizedException extends BaseException {

    public UnauthorizedException() {
        super(HttpStatus.UNAUTHORIZED.value(), "UNAUTHENTICATED", "Unauthorized");
    }

    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED.value(), "UNAUTHENTICATED", message);
    }
}
