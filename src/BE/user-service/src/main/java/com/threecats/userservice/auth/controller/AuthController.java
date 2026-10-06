package com.threecats.userservice.auth.controller;

import com.threecats.userservice.auth.dto.request.LoginRequest;
import com.threecats.userservice.auth.dto.request.registerUserRequest;
import com.threecats.userservice.auth.dto.response.LoginResponse;
import com.threecats.userservice.auth.service.AuthService;
import com.threecats.common.dto.response.ApiResponse;
import com.threecats.userservice.auth.service.AuthServiceImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    ApiResponse<?> registerUser(@Valid @RequestBody registerUserRequest request) {
        authService.registerUser(request);
        return ApiResponse.success("Register successfully!");
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        // 1. Gọi AuthService để xử lý đăng nhập
        AuthService.LoginResult result = authService.login(request);

        // 2. Trả về Response chứa AccessToken trong Body và RefreshToken trong Header Set-Cookie
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, result.refreshTokenCookie().toString())
                .body(ApiResponse.success(result.response(), "Login successfully"));
    }
}
