package com.threecats.userservice.controller;

import com.threecats.userservice.dto.request.UserRequest;
import com.threecats.userservice.dto.response.ApiResponse;
import com.threecats.userservice.dto.response.UserResponse;
import com.threecats.userservice.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user")
public class UserController {
    private final UserService userService;

    @GetMapping("/{id}")
    public ApiResponse<?> getUserById(@PathVariable UUID id) {
        return ApiResponse.success(userService.findUserById(id));
    }

    @PostMapping("/create")
    public ApiResponse<?> createUser(@Valid @RequestBody UserRequest req) {
        return ApiResponse.success(userService.createUser(req));
    }
}
