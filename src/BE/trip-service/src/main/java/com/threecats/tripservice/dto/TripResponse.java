package com.threecats.tripservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TripResponse {
    private UUID id;
    private UUID passenger_id;
    private String status;
    private String payment_status;
    private UUID quote_id;
    private PointDto pickup;
    private PointDto dropoff;
    private String vehicle_type;
    private MoneyDto estimated_fare;
    private MoneyDto final_fare;
    private UUID driver_id;
    private UUID driver_user_id;
    private UUID attempt_id;
    private Long version;
    private String created_at;
    private String updated_at;
}
