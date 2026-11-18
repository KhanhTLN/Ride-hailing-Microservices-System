package com.threecats.pricingservice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuoteInputRequest {
    @NotNull
    @Valid
    private PointDto pickup;

    @NotNull
    @Valid
    private PointDto dropoff;

    @NotNull
    @Pattern(regexp = "^(MOTORBIKE|CAR)$", message = "vehicle_type must be either MOTORBIKE or CAR")
    private String vehicle_type;
}
