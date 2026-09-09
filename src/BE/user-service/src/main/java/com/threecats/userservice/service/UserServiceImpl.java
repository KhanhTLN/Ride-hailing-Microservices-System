package com.threecats.userservice.service;


import com.threecats.userservice.dto.response.UserResponse;
import com.threecats.userservice.entity.User;
import com.threecats.userservice.exception.UserNotFoundException;
import com.threecats.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.crossstore.ChangeSetPersister;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService{
   private final UserRepository userRepository;

   @Override
   public UserResponse findUserById(String id){
       Optional<User> userOpt = userRepository.findById(id);

       User user;
       if(userOpt.isPresent()){
           user = userOpt.get();
       } else {
           throw new UserNotFoundException();
       }
        UserResponse userResponse = com.threecats.userservice.dto.response.UserResponse.builder()
                .id(user.getId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .build();
        return userResponse;
   }


}
