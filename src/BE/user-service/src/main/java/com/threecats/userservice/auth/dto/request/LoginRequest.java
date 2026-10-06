package com.threecats.userservice.auth.dto.request;

import lombok.Getter;

@Getter
public class LoginRequest {
    String username;
    String password;
}
