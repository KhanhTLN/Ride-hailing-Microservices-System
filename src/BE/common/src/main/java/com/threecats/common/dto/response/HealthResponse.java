package com.threecats.common.dto.response;

public record HealthResponse(String status) {
    public static HealthResponse serving() {
        return new HealthResponse("SERVING");
    }

    public static HealthResponse notServing() {
        return new HealthResponse("NOT_SERVING");
    }
}
