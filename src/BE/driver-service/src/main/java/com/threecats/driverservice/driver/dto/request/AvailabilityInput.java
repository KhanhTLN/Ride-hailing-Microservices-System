package com.threecats.driverservice.driver.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvailabilityInput {

    @NotNull(message = "Accepting trips is required")
    @JsonProperty("accepting_trips")
    private Boolean acceptingTrips;
}
