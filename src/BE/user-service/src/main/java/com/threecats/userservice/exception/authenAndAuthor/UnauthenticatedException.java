package com.threecats.userservice.exception.authenAndAuthor;

import com.threecats.userservice.exception.BaseException;

public abstract class UnauthenticatedException extends BaseException {
    public UnauthenticatedException() { super(401, "Unauthenticated"); }

    public UnauthenticatedException(String message) { super(401, message); }
}
