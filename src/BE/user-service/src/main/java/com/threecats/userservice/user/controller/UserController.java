package com.threecats.userservice.user.controller;

import com.threecats.userservice.user.dto.request.UserRequest;
import com.threecats.userservice.user.dto.response.ApiResponse;
import com.threecats.userservice.user.service.UserService;
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
