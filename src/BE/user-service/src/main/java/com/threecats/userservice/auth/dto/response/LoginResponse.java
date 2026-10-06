package com.threecats.userservice.auth.dto.response;

import com.threecats.userservice.enums.Role;
import lombok.*;

import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private String accessToken;

    @Builder.Default
    private String tokenType = "Bearer";

    private Long expiresIn;

    private UserSummaryResponse user;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserSummaryResponse {
        private UUID id;
        private String email;
        private String fullName;
        private String avatarUrl;
        private Role role;
    }
}
