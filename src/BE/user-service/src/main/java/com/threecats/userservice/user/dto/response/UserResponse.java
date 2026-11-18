package com.threecats.userservice.user.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.threecats.userservice.enums.AccountStatus;
import com.threecats.userservice.enums.Role;
import com.threecats.userservice.user.entity.User;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserResponse {
    private UUID id;
    private String email;

    @JsonProperty("display_name")
    private String displayName;

    private List<String> roles;

    @JsonProperty("created_at")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private OffsetDateTime createdAt;

    // Internal getters/fields kept for internal methods & gRPC (not serialized to REST JSON)
    @JsonIgnore
    private String username;
    @JsonIgnore
    private String firstName;
    @JsonIgnore
    private String lastName;
    @JsonIgnore
    private String phone;
    @JsonIgnore
    private Role role;
    @JsonIgnore
    private AccountStatus status;
    @JsonIgnore
    private OffsetDateTime updatedAt;
    @JsonIgnore
    private OffsetDateTime deletedAt;

    public static UserResponse mapFromEntity(User user) {
        String roleName = user.getRole() != null ? user.getRole().name() : Role.PASSENGER.name();
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .roles(Collections.singletonList(roleName))
                .createdAt(user.getCreatedAt())
                .username(user.getUsername())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .phone(user.getPhone())
                .role(user.getRole())
                .status(user.getStatus())
                .updatedAt(user.getUpdatedAt())
                .deletedAt(user.getDeletedAt())
                .build();
    }
}
