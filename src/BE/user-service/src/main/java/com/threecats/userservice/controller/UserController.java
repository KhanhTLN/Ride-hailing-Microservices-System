package com.threecats.userservice.controller;

import com.threecats.userservice.dto.response.ApiResponse;
import com.threecats.userservice.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user")
public class UserController {
    private final UserService userService;

    @GetMapping("/{id}")
    public ApiResponse<?> getUserById(@PathVariable String id) {
        return ApiResponse.success(userService.findUserById(id));
    }
}
