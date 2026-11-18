package com.threecats.driverservice.driver.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    private UUID id;

    @JsonProperty("user_id")
    private UUID userId;

    @JsonProperty("license_number")
    private String licenseNumber;

    @JsonProperty("account_status")
    private String accountStatus;

    @JsonProperty("accepting_trips")
    private Boolean acceptingTrips;

    @JsonProperty("created_at")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private OffsetDateTime createdAt;

    // Fields kept for backward compatibility with legacy endpoints / internal logic
    @JsonIgnore
    private String username;
    @JsonIgnore
    private String fullName;
    @JsonIgnore
    private String phone;
    @JsonIgnore
    private String email;
    @JsonIgnore
    private String identityCard;
    @JsonIgnore
    private DriverApprovalStatus approvalStatus;
    @JsonIgnore
    private VehicleType vehicleType;
    @JsonIgnore
    private String vehiclePlate;
    @JsonIgnore
    private String vehicleModel;
    @JsonIgnore
    private DriverActivityStatus activityStatus;
    @JsonIgnore
    private Double latitude;
    @JsonIgnore
    private Double longitude;
}
