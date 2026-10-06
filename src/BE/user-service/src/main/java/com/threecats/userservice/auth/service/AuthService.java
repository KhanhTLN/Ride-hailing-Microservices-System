package com.threecats.userservice.auth.service;

import com.threecats.userservice.auth.dto.request.registerUserRequest;
import com.threecats.userservice.user.dto.response.UserResponse;

public interface AuthService {
    UserResponse registerUser(registerUserRequest request);
}
