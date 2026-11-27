package com.threecats.tripservice.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TripCreateRequest {

    @NotNull
    private UUID quote_id;

    @NotNull
    @jakarta.validation.constraints.Pattern(regexp="SIMULATED")
    private String payment_method; // "SIMULATED"
}
