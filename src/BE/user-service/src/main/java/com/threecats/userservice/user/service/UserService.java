package com.threecats.userservice.user.service;

import ch.qos.logback.core.model.ComponentModel;
import com.threecats.userservice.user.dto.request.UpdateUserProfileRequest;
import com.threecats.userservice.user.dto.request.UserRequest;
import com.threecats.userservice.user.dto.response.UserResponse;

import java.util.List;
import java.util.UUID;

public interface UserService {
    UserResponse findUserById(UUID id);
    UserResponse createUser(UserRequest req);

    UserResponse updateUser(UUID id, UpdateUserProfileRequest request);
    void deleteUser(UUID id);
    List<UserResponse> getAllUsers();
}

