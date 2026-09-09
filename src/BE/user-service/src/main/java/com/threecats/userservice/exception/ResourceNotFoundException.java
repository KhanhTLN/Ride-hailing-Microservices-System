package com.threecats.userservice.exception;

public class ResourceNotFoundException extends BaseException {

    // 1. Constructor không tham số (dùng message mặc định)
    public ResourceNotFoundException() {
        super(404, "Resource not found");
    }

    // 2. Constructor nhận message tùy chỉnh
    public ResourceNotFoundException(String message) {
        super(404, message);
    }

    // 3. Constructor tiện lợi khi tìm theo ID/Email/Phone
    public ResourceNotFoundException(String fieldName, Object fieldValue) {
        super(404, String.format("User not found with %s: '%s'", fieldName, fieldValue));
    }
}