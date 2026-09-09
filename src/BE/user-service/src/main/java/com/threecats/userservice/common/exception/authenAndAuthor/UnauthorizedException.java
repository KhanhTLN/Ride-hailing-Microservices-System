package com.threecats.userservice.common.exception.authenAndAuthor;

import com.threecats.userservice.common.exception.BaseException;

public abstract class UnauthorizedException extends BaseException {
    public UnauthorizedException() { super(403, "Unthorized"); }

    public UnauthorizedException(String message) { super(403, message); }
}
