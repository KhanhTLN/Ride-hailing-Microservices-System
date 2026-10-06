package com.threecats.userservice.user.controller;

import com.threecats.userservice.user.dto.request.UpdateUserProfileRequest;
import com.threecats.userservice.user.dto.request.UserRequest;
import com.threecats.common.dto.response.ApiResponse;
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

    @PatchMapping("/{id}")
    public ApiResponse<?> updateUser(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserProfileRequest request) {
        return ApiResponse.success(userService.updateUser(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<?> deleteUser(
            @PathVariable UUID id
    ) {
        userService.deleteUser(id);

        return ApiResponse.success("Delete user " + id.toString() + "successfully");
    }

    @GetMapping
    public ApiResponse<?> getAllUser() {
        return ApiResponse.success(userService.getAllUsers());
    }
}
