package com.threecats.pricingservice.dto;

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
public class MoneyDto {
    @NotNull
    @Pattern(regexp = "^(0|[1-9][0-9]*)$")
    private String amount;

    @NotNull
    private String currency; // "VND"
}
