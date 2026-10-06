package com.threecats.userservice.auth.service;

import com.threecats.userservice.auth.dto.request.LoginRequest;
import com.threecats.userservice.auth.dto.request.registerUserRequest;
import com.threecats.userservice.auth.dto.response.LoginResponse;
import com.threecats.userservice.user.dto.response.UserResponse;
import org.springframework.http.ResponseCookie;

public interface AuthService {
    UserResponse registerUser(registerUserRequest request);
    LoginResult login(LoginRequest request);
    record LoginResult(LoginResponse response, ResponseCookie refreshTokenCookie) {}
}
