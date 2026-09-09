package com.threecats.userservice.exception;

public class UserNotFoundException extends BaseException {

    // 1. Constructor không tham số (dùng message mặc định)
    public UserNotFoundException() {
        super(404, "User not found");
    }

    // 2. Constructor nhận message tùy chỉnh
    public UserNotFoundException(String message) {
        super(404, message);
    }

    // 3. Constructor tiện lợi khi tìm theo ID/Email/Phone
    public UserNotFoundException(String fieldName, Object fieldValue) {
        super(404, String.format("User not found with %s: '%s'", fieldName, fieldValue));
    }
}