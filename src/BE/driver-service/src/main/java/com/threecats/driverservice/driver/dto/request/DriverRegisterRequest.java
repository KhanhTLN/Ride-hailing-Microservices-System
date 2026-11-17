package com.threecats.driverservice.driver.dto.request;

import com.threecats.driverservice.enums.VehicleType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverRegisterRequest {

    // 1. Thông tin tài khoản người dùng (sẽ ủy thác cho user-service tạo qua gRPC)
    @NotBlank(message = "Username is required")
    @Size(min = 6, message = "Username must be at least 6 characters")
    private String username;

    @NotBlank(message = "Password is required")
    @Size(min = 6, message = "Password must be at least 6 characters")
    private String password;

    @NotBlank(message = "First name is required")
    private String firstName;

    @NotBlank(message = "Last name is required")
    private String lastName;

    @NotBlank(message = "Phone is required")
    @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "Số điện thoại không hợp lệ")
    private String phone;

    @NotBlank(message = "Email is required")
    @Email(message = "Email is invalid")
    private String email;

    // 2. Giấy tờ pháp lý của tài xế
    @NotBlank(message = "Số căn cước công dân không được để trống")
    private String identityCard;

    @NotBlank(message = "Số giấy phép lái xe không được để trống")
    private String licenseNumber;

    // 3. Thông tin phương tiện
    @NotNull(message = "Loại phương tiện không được để trống")
    private VehicleType vehicleType;

    @NotBlank(message = "Biển số xe không được để trống")
    private String vehiclePlate;

    private String vehicleModel;
}
