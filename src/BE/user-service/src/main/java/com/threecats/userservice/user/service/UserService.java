package com.threecats.userservice.user.service;

import com.threecats.userservice.user.dto.request.UserRequest;
import com.threecats.userservice.user.dto.response.UserResponse;

import java.util.UUID;

public interface UserService {
    UserResponse findUserById(UUID id);
    UserResponse createUser(UserRequest req);
}

