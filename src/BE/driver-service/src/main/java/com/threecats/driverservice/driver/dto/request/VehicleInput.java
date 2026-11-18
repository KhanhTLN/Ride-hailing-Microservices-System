package com.threecats.driverservice.driver.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VehicleInput {

    @NotBlank(message = "Plate number is required")
    @Size(min = 1, max = 20, message = "Plate number must be between 1 and 20 characters")
    @JsonProperty("plate_number")
    private String plateNumber;

    @NotBlank(message = "Vehicle type is required")
    @JsonProperty("vehicle_type")
    private String vehicleType; // MOTORBIKE or CAR

    @NotBlank(message = "Brand is required")
    @Size(max = 80)
    private String brand;

    @NotBlank(message = "Model is required")
    @Size(max = 80)
    private String model;

    @NotBlank(message = "Color is required")
    @Size(max = 40)
    private String color;

    @NotNull(message = "Seat count is required")
    @Min(1)
    @Max(16)
    @JsonProperty("seat_count")
    private Integer seatCount;
}
