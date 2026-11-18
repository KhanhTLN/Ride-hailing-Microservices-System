package com.threecats.driverservice.driver.controller;

import com.threecats.common.dto.response.Ack;
import com.threecats.common.dto.response.ApiResponse;
import com.threecats.common.exception.authenAndAuthor.UnauthorizedException;
import com.threecats.driverservice.driver.dto.request.*;
import com.threecats.driverservice.driver.dto.response.*;
import com.threecats.driverservice.driver.service.DriverService;
import com.threecats.driverservice.driver.service.DriverIdempotencyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class DriverController {

    private final DriverService driverService;
    private final DriverIdempotencyService idempotency;

    private UUID getAuthenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new UnauthorizedException("Authentication token is required");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new UnauthorizedException("Invalid user ID in authentication token: " + authentication.getName());
        }
    }

    // =========================================================================
    // DS03 API CONTRACT v1.0.0 ENDPOINTS
    // =========================================================================

    @PostMapping("/api/v1/drivers/me")
    public ResponseEntity<DriverResponse> createDriver(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DriverCreate request,
            Authentication authentication) {
        UUID userId = getAuthenticatedUserId(authentication);
        DriverResponse response = idempotency.execute(userId.toString(), "createDriver", idempotencyKey, request,
                DriverResponse.class, () -> driverService.createDriver(userId, request));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/api/v1/drivers/me")
    public ResponseEntity<DriverResponse> getMyDriver(Authentication authentication) {
        UUID userId = getAuthenticatedUserId(authentication);
        DriverResponse response = driverService.getMyDriver(userId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/v1/admin/drivers")
    public ResponseEntity<List<DriverResponse>> getAllDrivers() {
        return ResponseEntity.ok(driverService.getAllDrivers());
    }

    @PatchMapping("/api/v1/admin/drivers/{driver_id}/status")
    public ResponseEntity<DriverResponse> approveDriver(
            @PathVariable("driver_id") UUID driverId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ApprovalRequest request, Authentication authentication) {
        DriverResponse response = idempotency.execute(getAuthenticatedUserId(authentication).toString(),
                "approveDriver", idempotencyKey, List.of(driverId, request), DriverResponse.class,
                () -> driverService.approveDriver(driverId, request));
        return ResponseEntity.ok(response);
    }

    @PutMapping("/api/v1/drivers/me/vehicle")
    public ResponseEntity<VehicleResponse> setActiveVehicle(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody VehicleInput request,
            Authentication authentication) {
        UUID userId = getAuthenticatedUserId(authentication);
        VehicleResponse response = idempotency.execute(userId.toString(), "setActiveVehicle", idempotencyKey, request,
                VehicleResponse.class, () -> driverService.setActiveVehicle(userId, request));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/v1/drivers/me/vehicle")
    public ResponseEntity<VehicleResponse> getActiveVehicle(Authentication authentication) {
        UUID userId = getAuthenticatedUserId(authentication);
        VehicleResponse response = driverService.getActiveVehicle(userId);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/api/v1/drivers/me/availability")
    public ResponseEntity<AvailabilityResponse> setAvailability(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AvailabilityInput request,
            Authentication authentication) {
        UUID userId = getAuthenticatedUserId(authentication);
        AvailabilityResponse response = idempotency.execute(userId.toString(), "setAvailability", idempotencyKey, request,
                AvailabilityResponse.class, () -> driverService.setAvailability(userId, request));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/v1/drivers/me/location-sessions")
    public ResponseEntity<LocationSessionResponse> startLocationSession(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        UUID userId = getAuthenticatedUserId(authentication);
        LocationSessionResponse response = idempotency.execute(userId.toString(), "startLocationSession", idempotencyKey, List.of(),
                LocationSessionResponse.class, () -> driverService.startLocationSession(userId));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/v1/drivers/me/heartbeat")
    public ResponseEntity<Ack> heartbeat(@Valid @RequestBody HeartbeatInput request, Authentication authentication) {
        UUID userId = getAuthenticatedUserId(authentication);
        Ack response = driverService.heartbeat(userId, request.sessionId());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/api/v1/drivers/me/location")
    public ResponseEntity<LocationResponse> updateLocation(
            @Valid @RequestBody LocationInput request,
            Authentication authentication) {
        UUID userId = getAuthenticatedUserId(authentication);
        LocationResponse response = driverService.updateLocation(userId, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/v1/drivers/me/location")
    public ResponseEntity<LocationResponse> getMyLocation(Authentication authentication) {
        UUID userId = getAuthenticatedUserId(authentication);
        LocationResponse response = driverService.getMyLocation(userId);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // LEGACY COMPATIBILITY ENDPOINTS (/api/drivers)
    // =========================================================================

    @PostMapping("/api/drivers/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DriverResponse> registerLegacy(@Valid @RequestBody DriverRegisterRequest request) {
        DriverResponse response = driverService.registerDriver(request);
        return ApiResponse.created(response);
    }

    @GetMapping("/api/drivers/{userId}")
    public ApiResponse<DriverResponse> getProfileLegacy(@PathVariable UUID userId) {
        DriverResponse response = driverService.getDriverProfile(userId);
        return ApiResponse.success(response);
    }
}
