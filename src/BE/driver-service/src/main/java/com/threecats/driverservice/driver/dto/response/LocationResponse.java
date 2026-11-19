package com.threecats.driverservice.driver.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.threecats.driverservice.driver.dto.PointDto;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocationResponse {

    private PointDto point;

    @JsonProperty("driver_id")
    private UUID driverId;

    @JsonProperty("session_id")
    private UUID sessionId;

    @JsonProperty("sequence_number")
    private Long sequenceNumber;

    @JsonProperty("recorded_at")
    private OffsetDateTime recordedAt;

    @JsonProperty("received_at")
    private OffsetDateTime receivedAt;
}
