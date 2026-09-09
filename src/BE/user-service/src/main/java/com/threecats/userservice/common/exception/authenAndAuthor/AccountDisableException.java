package com.threecats.userservice.common.exception.authenAndAuthor;

import com.threecats.userservice.common.exception.BaseException;

public abstract class AccountDisableException extends BaseException {
    public AccountDisableException() { super(423, "Account Disable"); }
    public AccountDisableException(String message) { super(423, message); }
}
