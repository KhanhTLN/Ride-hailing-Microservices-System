package com.threecats.userservice.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequest {
    private String email;
    private String username;

    @NotBlank(message = "Password is required")
    private String password;

    public String getIdentifier() {
        if (email != null && !email.isBlank()) {
            return email;
        }
        return username;
    }
}
