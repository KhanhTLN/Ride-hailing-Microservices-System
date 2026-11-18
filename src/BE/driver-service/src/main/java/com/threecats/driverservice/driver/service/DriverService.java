package com.threecats.driverservice.driver.service;

import com.threecats.common.dto.response.Ack;
import com.threecats.driverservice.driver.dto.request.*;
import com.threecats.driverservice.driver.dto.response.*;

import java.util.UUID;

public interface DriverService {
    // Contract methods
    DriverResponse createDriver(UUID userId, DriverCreate request);
    DriverResponse getMyDriver(UUID userId);
    DriverResponse approveDriver(UUID driverId, ApprovalRequest request);
    VehicleResponse setActiveVehicle(UUID userId, VehicleInput request);
    VehicleResponse getActiveVehicle(UUID userId);
    AvailabilityResponse setAvailability(UUID userId, AvailabilityInput request);
    LocationSessionResponse startLocationSession(UUID userId);
    Ack heartbeat(UUID userId);
    LocationResponse updateLocation(UUID userId, LocationInput request);
    LocationResponse getMyLocation(UUID userId);

    // Legacy methods
    DriverResponse registerDriver(DriverRegisterRequest request);
    DriverResponse getDriverProfile(UUID userId);
}
