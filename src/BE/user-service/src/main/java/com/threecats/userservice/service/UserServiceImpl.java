package com.threecats.userservice.service;


import com.threecats.userservice.dto.request.UserRequest;
import com.threecats.userservice.dto.response.UserResponse;
import com.threecats.userservice.entity.User;
import com.threecats.userservice.enums.AccountStatus;
import com.threecats.userservice.enums.Role;
import com.threecats.userservice.exception.resource.ResourceAlreadyExistException;
import com.threecats.userservice.exception.resource.ResourceNotFoundException;
import com.threecats.userservice.repository.UserRepository;
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
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .build();
    }

}
