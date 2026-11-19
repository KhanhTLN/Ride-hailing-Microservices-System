package com.threecats.driverservice.driver.service;

import java.time.Instant;
import java.util.UUID;

record DriverHeartbeatAccepted(UUID driverId, UUID sessionId, Instant receivedAt) {
}
