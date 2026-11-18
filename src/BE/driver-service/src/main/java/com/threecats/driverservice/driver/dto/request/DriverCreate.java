package com.threecats.driverservice.driver.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverCreate {

    @NotBlank(message = "License number is required")
    @Size(min = 1, max = 50, message = "License number must be between 1 and 50 characters")
    @JsonProperty("license_number")
    private String licenseNumber;
}
