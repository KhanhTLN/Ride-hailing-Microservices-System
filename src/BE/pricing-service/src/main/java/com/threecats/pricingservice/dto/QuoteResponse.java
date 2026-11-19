package com.threecats.pricingservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuoteResponse {
    private String id;
    private String passenger_id;
    private PointDto pickup;
    private PointDto dropoff;
    private String vehicle_type;
    private Long estimated_distance_m;
    private Long estimated_duration_s;
    private MoneyDto fare;
    private String pricing_version;
    private String expires_at;
}
