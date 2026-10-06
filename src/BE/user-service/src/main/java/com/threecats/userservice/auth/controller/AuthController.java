package com.threecats.userservice.auth.controller;

import com.threecats.userservice.auth.dto.request.registerUserRequest;
import com.threecats.userservice.auth.service.AuthService;
import com.threecats.userservice.user.dto.response.ApiResponse;
import com.threecats.userservice.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
}
