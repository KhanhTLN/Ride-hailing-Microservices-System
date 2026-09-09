package com.threecats.userservice.service;

import com.threecats.userservice.dto.request.UserRequest;
import com.threecats.userservice.dto.response.UserResponse;
import org.springframework.stereotype.Service;

import java.util.UUID;

public interface UserService {
    UserResponse findUserById(UUID id);
    UserResponse createUser(UserRequest req);
}

