package com.threecats.userservice.common.exception.authenAndAuthor;

import com.threecats.userservice.common.exception.BaseException;

public abstract class UnauthenticatedException extends BaseException {
    public UnauthenticatedException() { super(401, "Unauthenticated"); }

    public UnauthenticatedException(String message) { super(401, message); }
}
