package com.threecats.common.exception.authenAndAuthor;

import com.threecats.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class AccountLockedException extends BaseException {

    public AccountLockedException() {
        super(HttpStatus.FORBIDDEN.value(), "FORBIDDEN", "Account is locked");
    }

    public AccountLockedException(String message) {
        super(HttpStatus.FORBIDDEN.value(), "FORBIDDEN", message);
    }
}
