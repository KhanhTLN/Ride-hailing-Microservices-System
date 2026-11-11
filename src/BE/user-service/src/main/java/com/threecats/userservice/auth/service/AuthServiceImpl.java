package com.threecats.userservice.auth.service;

import com.threecats.common.exception.resource.ResourceNotFoundException;
import com.threecats.userservice.auth.dto.request.LoginRequest;
import com.threecats.userservice.auth.dto.request.registerUserRequest;
import com.threecats.userservice.auth.dto.response.LoginResponse;
import com.threecats.userservice.enums.AccountStatus;
import com.threecats.userservice.enums.Role;
import com.threecats.common.exception.resource.ResourceAlreadyExistException;
import com.threecats.userservice.security.JwtTokenProvider;
import com.threecats.userservice.user.dto.response.UserResponse;
import com.threecats.userservice.user.entity.User;
import com.threecats.userservice.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService{

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthenticationManager authenticationManager;
    private final PasswordEncoder passwordEncoder;

    @Override
    public UserResponse registerUser(registerUserRequest request) {

        if(userRepository.findByUsername(request.getUsername()).isPresent()) {
            throw new ResourceAlreadyExistException("Username has already in use");
        }
        if(userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new ResourceAlreadyExistException("Email has already in use");
        }
        if(userRepository.findByPhone(request.getPhone()).isPresent()) {
            throw new ResourceAlreadyExistException("Phone number has already in use");
        }

        User user = User.builder()
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .phone(request.getPhone())
                .email(request.getEmail())
                .createdAt(OffsetDateTime.now())
                .updatedAt(null)
                .deletedAt(null)
                .role(Role.CUSTOMER)
                .status(AccountStatus.INACTIVE)
                .build();

        userRepository.save(user);

        return tranferUserToUserResponse(user);
    }

    @Override
    public LoginResult login(LoginRequest request) {

        // 1. Xác thực thông tin đăng nhập qua Spring Security
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getUsername(),
                        request.getPassword()
                )
        );

        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getRole());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        // sau nay luu refresh token vao redis

        ResponseCookie cookie = jwtTokenProvider.createRefreshTokenCookie(refreshToken);

        LoginResponse response = LoginResponse.builder()
                .accessToken(accessToken)
                .expiresIn(jwtTokenProvider.getAccessTokenExpirationInSeconds())
                .user(LoginResponse.UserSummaryResponse.builder()
                        .id(user.getId())
                        .email(user.getEmail())
                        .fullName(user.getFirstName() + user.getLastName())
                        .role(user.getRole())
                        .avatarUrl("avatarUrl")
                        .build())
                .build();

        return new LoginResult(response, cookie);

    }

    private UserResponse tranferUserToUserResponse (User user) {
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .role(user.getRole())
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .deletedAt(user.getDeletedAt())
                .build();
    }
}
