package com.threecats.driverservice.driver.dto.response;

import com.threecats.driverservice.enums.DriverActivityStatus;
import com.threecats.driverservice.enums.DriverApprovalStatus;
import com.threecats.driverservice.enums.VehicleType;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverResponse {
    private UUID userId;
    private String username;
    private String fullName;
    private String phone;
    private String email;

    private String identityCard;
    private String licenseNumber;
    private DriverApprovalStatus approvalStatus;

    private VehicleType vehicleType;
    private String vehiclePlate;
    private String vehicleModel;
    private DriverActivityStatus activityStatus;

    private Double latitude;
    private Double longitude;
    private OffsetDateTime createdAt;
}
