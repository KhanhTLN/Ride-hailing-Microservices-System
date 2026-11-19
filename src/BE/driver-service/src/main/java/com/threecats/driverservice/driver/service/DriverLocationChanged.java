package com.threecats.driverservice.driver.service;

import java.time.Instant;
import java.util.UUID;

record DriverLocationChanged(UUID driverId, Instant receivedAt, Position position) {
    record Position(UUID sessionId, long sequence, String vehicleType, double longitude, double latitude) {
    }
}
