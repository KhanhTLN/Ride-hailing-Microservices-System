package com.threecats.driverservice.driver.controller;

import com.threecats.common.dto.response.ApiResponse;
import com.threecats.driverservice.driver.dto.request.DriverRegisterRequest;
import com.threecats.driverservice.driver.dto.response.DriverResponse;
import com.threecats.driverservice.driver.service.DriverService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/drivers")
@RequiredArgsConstructor
public class DriverController {

    private final DriverService driverService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DriverResponse> register(@Valid @RequestBody DriverRegisterRequest request) {
        DriverResponse response = driverService.registerDriver(request);
        return ApiResponse.created(response);
    }

    @GetMapping("/{userId}")
    public ApiResponse<DriverResponse> getProfile(@PathVariable UUID userId) {
        DriverResponse response = driverService.getDriverProfile(userId);
        return ApiResponse.success(response);
    }
}
