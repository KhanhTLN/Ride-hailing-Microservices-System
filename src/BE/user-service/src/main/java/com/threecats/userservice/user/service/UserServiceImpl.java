package com.threecats.userservice.user.service;

import com.threecats.common.exception.authenAndAuthor.UnauthorizedException;
import com.threecats.common.exception.resource.ResourceAlreadyExistException;
import com.threecats.common.exception.resource.ResourceNotFoundException;
import com.threecats.userservice.enums.AccountStatus;
import com.threecats.userservice.enums.Role;
import com.threecats.userservice.user.dto.request.UpdateUserProfileRequest;
import com.threecats.userservice.user.dto.request.UserRequest;
import com.threecats.userservice.user.dto.response.UserResponse;
import com.threecats.userservice.user.entity.User;
import com.threecats.userservice.user.mapper.UserMapper;
import com.threecats.userservice.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    @Override
    public UserResponse getCurrentUser(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new UnauthorizedException("User is not authenticated");
        }

        User user = null;
        try {
            UUID id = UUID.fromString(identifier);
            user = userRepository.findById(id).orElse(null);
        } catch (IllegalArgumentException ignored) {
        }

        if (user == null) {
            user = userRepository.findByEmail(identifier)
                    .or(() -> userRepository.findByUsername(identifier))
                    .orElseThrow(() -> new ResourceNotFoundException("User not found with identifier: " + identifier));
        }

        return UserResponse.mapFromEntity(user);
    }

    @Override
    public UserResponse findUserById(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));

        return UserResponse.mapFromEntity(user);
    }

    @Override
    public UserResponse createUser(UserRequest req) {
        if (userRepository.findByPhone(req.getPhone()).isPresent()) {
            throw new ResourceAlreadyExistException("Phone already exists");
        }
        if (userRepository.findByEmail(req.getEmail()).isPresent()) {
            throw new ResourceAlreadyExistException("Email already exists");
        }

        User user = User.builder()
                .firstName(req.getFirstName())
                .lastName(req.getLastName())
                .displayName((req.getLastName() + " " + req.getFirstName()).trim())
                .email(req.getEmail())
                .phone(req.getPhone())
                .password(req.getPassword())
                .role(Role.PASSENGER)
                .status(AccountStatus.ACTIVE)
                .build();

        userRepository.save(user);
        return UserResponse.mapFromEntity(user);
    }

    @Override
    public UserResponse updateUser(UUID id, UpdateUserProfileRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        validateUniqueFields(user, request);

        userMapper.updateUserFromDto(request, user);

        User updatedUser = userRepository.save(user);
        return UserResponse.mapFromEntity(updatedUser);
    }

    @Override
    public void deleteUser(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        userRepository.delete(user);
    }

    @Override
    public List<UserResponse> getAllUsers() {
        return userRepository.findAll()
                .stream()
                .map(UserResponse::mapFromEntity)
                .toList();
    }

    private void validateUniqueFields(User currentUser, UpdateUserProfileRequest request) {
        if (request.getEmail() != null && !request.getEmail().equalsIgnoreCase(currentUser.getEmail())) {
            if (userRepository.findByEmail(request.getEmail()).isPresent()) {
                throw new ResourceAlreadyExistException("Email already exists");
            }
        }

        if (request.getPhone() != null && !request.getPhone().equalsIgnoreCase(currentUser.getPhone())) {
            if (userRepository.findByPhone(request.getPhone()).isPresent()) {
                throw new ResourceAlreadyExistException("Phone number already exists");
            }
        }
    }
}
