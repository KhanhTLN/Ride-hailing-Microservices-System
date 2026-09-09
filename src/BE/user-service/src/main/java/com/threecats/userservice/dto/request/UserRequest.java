package com.threecats.userservice.dto.request;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserRequest {
    String email;
    String phone;
    String password;
    String lastName;
    String firstName;
}
