package com.threecats.userservice.exception.authenAndAuthor;

import com.threecats.userservice.exception.BaseException;

public abstract class UnauthorizedException extends BaseException {
    public UnauthorizedException() { super(403, "Unthorized"); }

    public UnauthorizedException(String message) { super(403, message); }
}
