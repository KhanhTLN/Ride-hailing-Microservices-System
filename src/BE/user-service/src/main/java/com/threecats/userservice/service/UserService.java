package com.threecats.userservice.service;

import com.threecats.userservice.dto.response.UserResponse;
import org.springframework.stereotype.Service;

public interface UserService {
    UserResponse findUserById(String id);
}

