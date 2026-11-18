package com.threecats.driverservice.driver.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.threecats.driverservice.driver.dto.PointDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocationInput {

    @NotNull(message = "Session ID is required")
    @JsonProperty("session_id")
    private UUID sessionId;

    @NotNull(message = "Sequence number is required")
    @Min(value = 0, message = "Sequence number must be non-negative")
    @JsonProperty("sequence_number")
    private Long sequenceNumber;

    @NotNull(message = "Point is required")
    @Valid
    private PointDto point;

    @NotNull(message = "Recorded at is required")
    @JsonProperty("recorded_at")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private OffsetDateTime recordedAt;
}
