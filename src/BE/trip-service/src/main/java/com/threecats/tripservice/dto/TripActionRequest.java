package com.threecats.tripservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TripActionRequest {
    @jakarta.validation.constraints.NotNull
    @jakarta.validation.constraints.Min(1)
    @jakarta.validation.constraints.Max(2147483647L)
    private Long expected_version;
    private java.util.UUID attempt_id;
    private java.util.UUID reservation_id;
    @jakarta.validation.constraints.Size(min=1,max=300)
    private String reason;
}
