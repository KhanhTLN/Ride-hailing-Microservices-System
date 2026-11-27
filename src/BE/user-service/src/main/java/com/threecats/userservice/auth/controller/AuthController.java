package com.threecats.userservice.auth.controller;

import com.threecats.common.dto.response.Ack;
import com.threecats.common.dto.response.ApiResponse;
import com.threecats.userservice.auth.dto.request.LoginRequest;
import com.threecats.userservice.auth.dto.request.RefreshRequest;
import com.threecats.userservice.auth.dto.request.RegisterRequest;
import com.threecats.userservice.auth.dto.request.registerUserRequest;
import com.threecats.userservice.auth.dto.response.LoginResponse;
import com.threecats.userservice.auth.dto.response.TokenPair;
import com.threecats.userservice.auth.service.AuthService;
import com.threecats.userservice.user.dto.response.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final com.threecats.userservice.auth.service.RegistrationIdempotency registration;

    // =========================================================================
    // DS03 API CONTRACT v1.0.0 ENDPOINTS (/api/v1/auth)
    // =========================================================================

    @PostMapping("/api/v1/auth/register")
    public ResponseEntity<UserResponse> register(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RegisterRequest request) {
        UserResponse user = registration.execute(idempotencyKey,request,() -> authService.register(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(user);
    }

    @PostMapping("/api/v1/auth/login")
    public ResponseEntity<TokenPair> login(@Valid @RequestBody LoginRequest request) {
        TokenPair tokenPair = authService.login(request);
        return ResponseEntity.ok(tokenPair);
    }

    @PostMapping("/api/v1/auth/refresh")
    public ResponseEntity<TokenPair> refresh(@Valid @RequestBody RefreshRequest request) {
        TokenPair tokenPair = authService.refreshToken(request);
        return ResponseEntity.ok(tokenPair);
    }

    @PostMapping("/api/v1/auth/logout")
    public ResponseEntity<Ack> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request);
        return ResponseEntity.ok(Ack.of(true));
    }

    // =========================================================================
    // LEGACY COMPATIBILITY ENDPOINTS (/api/auth)
    // =========================================================================

    @PostMapping("/api/auth/register/customer")
    public ResponseEntity<ApiResponse<?>> registerUserLegacy(@Valid @RequestBody registerUserRequest request) {
        authService.registerUserWithRole(request, "CUSTOMER");
        return ResponseEntity.ok(ApiResponse.success("Register successfully!"));
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<ApiResponse<LoginResponse>> loginLegacy(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginResult result = authService.loginLegacy(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, result.refreshTokenCookie().toString())
                .body(ApiResponse.success(result.response(), "Login successfully"));
    }
}
