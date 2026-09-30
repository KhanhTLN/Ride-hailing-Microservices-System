package com.threecats.userservice.user.service;


import com.threecats.userservice.user.dto.request.UserRequest;
import com.threecats.userservice.user.dto.response.UserResponse;
import com.threecats.userservice.user.entity.User;
import com.threecats.userservice.common.enums.AccountStatus;
import com.threecats.userservice.common.enums.Role;
import com.threecats.userservice.common.exception.resource.ResourceAlreadyExistException;
import com.threecats.userservice.common.exception.resource.ResourceNotFoundException;
import com.threecats.userservice.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService{
    private final UserRepository userRepository;

    @Override
    public UserResponse findUserById(UUID id){
        Optional<User> userOpt = userRepository.findById(id);

        User user;
        if(userOpt.isPresent()){
           user = userOpt.get();
        } else {
            throw new ResourceNotFoundException("User not found!");
        }

        return tranferUserToUserResponse(user);
    }

    @Override
    public UserResponse createUser(UserRequest req) {
        if(userRepository.findByPhone(req.getPhone()).isPresent()) {
            throw new ResourceAlreadyExistException("Phone is exists");
        }
        if(userRepository.findByEmail(req.getEmail()).isPresent()) {
            throw new ResourceAlreadyExistException("Email is exists");
        }

        User user = User.builder()
                .firstName(req.getFirstName())
                .lastName(req.getLastName())
                .email(req.getEmail())
                .phone(req.getPhone())
                .password(req.getPassword())
                .role(Role.CUSTOMER)
                .status(AccountStatus.ACTIVE)
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
