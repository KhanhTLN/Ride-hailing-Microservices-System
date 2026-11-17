package com.threecats.userservice.auth.service;

import com.threecats.userservice.auth.dto.request.LoginRequest;
import com.threecats.userservice.auth.dto.request.registerUserRequest;
import com.threecats.userservice.auth.dto.response.LoginResponse;
import com.threecats.userservice.user.dto.response.UserResponse;
import org.springframework.http.ResponseCookie;

public interface AuthService {
    UserResponse registerUserWithRole(registerUserRequest request, String role);
    UserResponse registerDriverUser(String username, String password, String firstName, String lastName, String phone, String email);
    void rollbackUser(java.util.UUID userId);
    LoginResult login(LoginRequest request);
    record LoginResult(LoginResponse response, ResponseCookie refreshTokenCookie) {}
}
