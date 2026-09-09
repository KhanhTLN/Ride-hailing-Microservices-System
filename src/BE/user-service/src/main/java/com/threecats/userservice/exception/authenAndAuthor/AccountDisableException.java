package com.threecats.userservice.exception.authenAndAuthor;

import com.threecats.userservice.exception.BaseException;

public abstract class AccountDisableException extends BaseException {
    public AccountDisableException() { super(423, "Account Disable"); }
    public AccountDisableException(String message) { super(423, message); }
}
