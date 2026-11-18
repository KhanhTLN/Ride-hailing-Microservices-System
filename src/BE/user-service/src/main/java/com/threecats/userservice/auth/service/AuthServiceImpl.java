package com.threecats.userservice.auth.service;

import com.threecats.common.exception.authenAndAuthor.UnauthorizedException;
import com.threecats.common.exception.resource.ResourceAlreadyExistException;
import com.threecats.common.exception.resource.ResourceNotFoundException;
import com.threecats.common.exception.validationAndRequest.InvalidInputException;
import com.threecats.userservice.auth.dto.request.LoginRequest;
import com.threecats.userservice.auth.dto.request.RefreshRequest;
import com.threecats.userservice.auth.dto.request.RegisterRequest;
import com.threecats.userservice.auth.dto.request.registerUserRequest;
import com.threecats.userservice.auth.dto.response.LoginResponse;
import com.threecats.userservice.auth.dto.response.TokenPair;
import com.threecats.userservice.enums.AccountStatus;
import com.threecats.userservice.enums.Role;
import com.threecats.userservice.security.JwtTokenProvider;
import com.threecats.userservice.user.dto.response.UserResponse;
import com.threecats.userservice.user.entity.User;
import com.threecats.userservice.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthenticationManager authenticationManager;
    private final PasswordEncoder passwordEncoder;

    @Override
    public UserResponse register(RegisterRequest request) {
        String roleStr = request.getRole() != null ? request.getRole().trim().toUpperCase() : "PASSENGER";
        if (!"PASSENGER".equals(roleStr) && !"DRIVER".equals(roleStr) && !"CUSTOMER".equals(roleStr)) {
            throw new InvalidInputException("Role must be PASSENGER or DRIVER");
        }

        String email = request.getEmail().trim().toLowerCase();
        if (userRepository.findByEmail(email).isPresent()) {
            throw new ResourceAlreadyExistException("Email is already in use");
        }

        Role role = Role.fromString(roleStr);

        User user = User.builder()
                .username(email)
                .email(email)
                .displayName(request.getDisplayName().trim())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(role)
                .status(AccountStatus.ACTIVE)
                .createdAt(OffsetDateTime.now())
                .build();

        user = userRepository.save(user);
        log.info("Registered new user with id: {}, role: {}", user.getId(), role);
        return UserResponse.mapFromEntity(user);
    }

    @Override
    public TokenPair login(LoginRequest request) {
        String identifier = request.getIdentifier();
        if (identifier == null || identifier.isBlank()) {
            throw new InvalidInputException("Email or username is required");
        }

        // Authenticate via Spring Security
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(identifier, request.getPassword())
        );

        User user = userRepository.findByEmail(identifier)
                .or(() -> userRepository.findByUsername(identifier))
                .orElseThrow(() -> new ResourceNotFoundException("User not found with identifier: " + identifier));

        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getRole());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        return TokenPair.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getAccessTokenExpirationInSeconds())
                .user(UserResponse.mapFromEntity(user))
                .build();
    }

    @Override
    public TokenPair refreshToken(RefreshRequest request) {
        String token = request.getRefreshToken();
        if (!jwtTokenProvider.validateToken(token)) {
            throw new UnauthorizedException("Invalid or expired refresh token");
        }

        String userIdStr = jwtTokenProvider.getUserIdFromJwt(token);
        User user = userRepository.findById(UUID.fromString(userIdStr))
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        String newAccessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getRole());
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        return TokenPair.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getAccessTokenExpirationInSeconds())
                .user(UserResponse.mapFromEntity(user))
                .build();
    }

    @Override
    public void logout(RefreshRequest request) {
        // Contract: Revoke refresh token; repeated revocation succeeds
        log.info("Logout request received for refresh token");
    }

    @Override
    public UserResponse registerUserWithRole(registerUserRequest request, String role) {
        if (userRepository.findByUsername(request.getUsername()).isPresent()) {
            throw new ResourceAlreadyExistException("Username has already in use");
        }
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new ResourceAlreadyExistException("Email has already in use");
        }
        if (userRepository.findByPhone(request.getPhone()).isPresent()) {
            throw new ResourceAlreadyExistException("Phone number has already in use");
        }

        User user = User.builder()
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .displayName((request.getLastName() + " " + request.getFirstName()).trim())
                .phone(request.getPhone())
                .email(request.getEmail())
                .createdAt(OffsetDateTime.now())
                .role(Role.fromString(role))
                .status(AccountStatus.INACTIVE)
                .build();

        user = userRepository.save(user);
        return UserResponse.mapFromEntity(user);
    }

    @Override
    public UserResponse registerDriverUser(String username, String password, String firstName, String lastName, String phone, String email) {
        if (userRepository.findByUsername(username).isPresent()) {
            throw new ResourceAlreadyExistException("Username has already in use");
        }
        if (userRepository.findByEmail(email).isPresent()) {
            throw new ResourceAlreadyExistException("Email has already in use");
        }
        if (userRepository.findByPhone(phone).isPresent()) {
            throw new ResourceAlreadyExistException("Phone number has already in use");
        }

        User user = User.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .firstName(firstName)
                .lastName(lastName)
                .displayName((lastName + " " + firstName).trim())
                .phone(phone)
                .email(email)
                .createdAt(OffsetDateTime.now())
                .role(Role.DRIVER)
                .status(AccountStatus.ACTIVE)
                .build();

        user = userRepository.save(user);
        return UserResponse.mapFromEntity(user);
    }

    @Override
    public void rollbackUser(UUID userId) {
        userRepository.findById(userId).ifPresent(userRepository::delete);
    }

    @Override
    public LoginResult loginLegacy(LoginRequest request) {
        TokenPair pair = login(request);
        ResponseCookie cookie = jwtTokenProvider.createRefreshTokenCookie(pair.getRefreshToken());

        User user = userRepository.findById(pair.getUser().getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        LoginResponse legacyResponse = LoginResponse.builder()
                .accessToken(pair.getAccessToken())
                .expiresIn(pair.getExpiresIn())
                .user(LoginResponse.UserSummaryResponse.builder()
                        .id(user.getId())
                        .email(user.getEmail())
                        .fullName(user.getDisplayName())
                        .role(user.getRole())
                        .avatarUrl("avatarUrl")
                        .build())
                .build();

        return new LoginResult(legacyResponse, cookie);
    }
}
