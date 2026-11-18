package com.threecats.userservice.auth.service;

import com.threecats.userservice.auth.dto.request.LoginRequest;
import com.threecats.userservice.auth.dto.request.RefreshRequest;
import com.threecats.userservice.auth.dto.request.RegisterRequest;
import com.threecats.userservice.auth.dto.request.registerUserRequest;
import com.threecats.userservice.auth.dto.response.LoginResponse;
import com.threecats.userservice.auth.dto.response.TokenPair;
import com.threecats.userservice.user.dto.response.UserResponse;
import org.springframework.http.ResponseCookie;

import java.util.UUID;

public interface AuthService {
    // Contract methods
    UserResponse register(RegisterRequest request);
    TokenPair login(LoginRequest request);
    TokenPair refreshToken(RefreshRequest request);
    void logout(RefreshRequest request);

    // Existing methods kept for backward compatibility & gRPC
    UserResponse registerUserWithRole(registerUserRequest request, String role);
    UserResponse registerDriverUser(String username, String password, String firstName, String lastName, String phone, String email);
    void rollbackUser(UUID userId);
    LoginResult loginLegacy(LoginRequest request);

    record LoginResult(LoginResponse response, ResponseCookie refreshTokenCookie) {}
}
