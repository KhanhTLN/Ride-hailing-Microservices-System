package com.threecats.pricingservice.model;

import com.threecats.pricingservice.dto.MoneyDto;
import com.threecats.pricingservice.dto.PointDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuoteRecord {
    private String id;
    private String passengerId;
    private PointDto pickup;
    private PointDto dropoff;
    private String vehicleType;
    private Long estimatedDistanceM;
    private Long estimatedDurationS;
    private MoneyDto fare;
    private String pricingVersion;
    private Instant expiresAt;
    private Instant createdAt;
}
