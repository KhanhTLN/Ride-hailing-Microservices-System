package com.threecats.userservice.user.controller;

import com.threecats.common.dto.response.ApiResponse;
import com.threecats.userservice.user.dto.request.UpdateUserProfileRequest;
import com.threecats.userservice.user.dto.request.UserRequest;
import com.threecats.userservice.user.dto.response.UserResponse;
import com.threecats.userservice.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    private void requireOwner(UUID id) {
        if(!com.threecats.common.security.CurrentUser.hasRole("ADMIN") && !com.threecats.common.security.CurrentUser.id().equals(id))
            throw new org.springframework.security.access.AccessDeniedException("Only the owner or admin can access this user");
    }

    // =========================================================================
    // DS03 API CONTRACT v1.0.0 ENDPOINTS
    // =========================================================================

    @GetMapping("/api/v1/users/me")
    public ResponseEntity<UserResponse> getCurrentUser(Authentication authentication) {
        String identifier = authentication != null ? authentication.getName() : null;
        UserResponse response = userService.getCurrentUser(identifier);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/v1/users/{id}")
    public ResponseEntity<UserResponse> getUserByIdContract(@PathVariable UUID id) {
        requireOwner(id);
        return ResponseEntity.ok(userService.findUserById(id));
    }

    // =========================================================================
    // LEGACY COMPATIBILITY ENDPOINTS (/api/user)
    // =========================================================================

    @GetMapping("/api/user/{id}")
    public ApiResponse<?> getUserById(@PathVariable UUID id) {
        requireOwner(id);
        return ApiResponse.success(userService.findUserById(id));
    }

    @PostMapping("/api/user/create")
    public ApiResponse<?> createUser(@Valid @RequestBody UserRequest req) {
        com.threecats.common.security.CurrentUser.require("ADMIN");
        return ApiResponse.success(userService.createUser(req));
    }

    @PatchMapping("/api/user/{id}")
    public ApiResponse<?> updateUser(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserProfileRequest request) {
        requireOwner(id);
        return ApiResponse.success(userService.updateUser(id, request));
    }

    @DeleteMapping("/api/user/{id}")
    public ApiResponse<?> deleteUser(@PathVariable UUID id) {
        requireOwner(id);
        userService.deleteUser(id);
        return ApiResponse.success("Delete user " + id.toString() + " successfully");
    }

    @GetMapping("/api/user")
    public ApiResponse<List<UserResponse>> getAllUser() {
        com.threecats.common.security.CurrentUser.require("ADMIN");
        return ApiResponse.success(userService.getAllUsers());
    }
}
