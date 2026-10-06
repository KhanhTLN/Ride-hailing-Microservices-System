package com.threecats.userservice.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateUserProfileRequest {
    private String username;

    private String firstName;

    private String lastName;

    @Pattern(regexp = "^$|^\\+?[0-9]{10,15}$", message = "Số điện thoại không hợp lệ")
    private String phone;

    @Email(message = "Email không hợp lệ")
    private String email;
}
