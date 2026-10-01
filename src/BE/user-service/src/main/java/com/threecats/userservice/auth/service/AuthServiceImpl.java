package com.threecats.userservice.auth.service;

import com.threecats.userservice.auth.dto.request.registerUserRequest;
import com.threecats.userservice.enums.AccountStatus;
import com.threecats.userservice.enums.Role;
import com.threecats.common.exception.resource.ResourceAlreadyExistException;
import com.threecats.userservice.user.dto.response.UserResponse;
import com.threecats.userservice.user.entity.User;
import com.threecats.userservice.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService{

    private final UserRepository userRepository;

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
                .password(request.getPassword())
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
