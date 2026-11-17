package com.threecats.driverservice.driver.service;

import com.threecats.driverservice.driver.dto.request.DriverRegisterRequest;
import com.threecats.driverservice.driver.dto.response.DriverResponse;

import java.util.UUID;

public interface DriverService {
    DriverResponse registerDriver(DriverRegisterRequest request);
    DriverResponse getDriverProfile(UUID userId);
}
