package com.threecats.userservice.user.dto.response;

import com.threecats.userservice.enums.AccountStatus;
import com.threecats.userservice.enums.Role;
import com.threecats.userservice.user.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@AllArgsConstructor
@Builder
public class UserResponse {
    private UUID id;
    private String username;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private Role role;
    private AccountStatus status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;

    public static UserResponse mapFromEntity(User user) {
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
