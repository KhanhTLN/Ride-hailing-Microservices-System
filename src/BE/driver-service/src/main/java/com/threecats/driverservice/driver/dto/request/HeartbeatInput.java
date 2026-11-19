package com.threecats.driverservice.driver.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record HeartbeatInput(@NotNull @JsonProperty("session_id") UUID sessionId) {
}
