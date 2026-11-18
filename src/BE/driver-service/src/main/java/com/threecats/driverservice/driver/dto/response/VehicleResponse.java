package com.threecats.driverservice.driver.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VehicleResponse {

    private UUID id;

    @JsonProperty("driver_id")
    private UUID driverId;

    @JsonProperty("plate_number")
    private String plateNumber;

    @JsonProperty("vehicle_type")
    private String vehicleType;

    private String brand;
    private String model;
    private String color;

    @JsonProperty("seat_count")
    private Integer seatCount;

    @JsonProperty("is_active")
    private Boolean isActive;
}
