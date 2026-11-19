package com.threecats.tripservice.controller;

import com.threecats.tripservice.dto.*;
import com.threecats.tripservice.service.TripService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class TripController {

    private final TripService tripService;

    @PostMapping("/api/v1/trips")
    public ResponseEntity<TripResponse> createTrip(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @Valid @RequestBody TripCreateRequest request) {

        UUID passengerId = userIdHeader != null && !userIdHeader.isBlank()
                ? UUID.fromString(userIdHeader)
                : UUID.randomUUID();

        String corrId = correlationId != null ? correlationId : UUID.randomUUID().toString();
        TripResponse response = tripService.createTrip(request, passengerId, idempotencyKey, corrId);

        return ResponseEntity.status(HttpStatus.CREATED)
                .header("X-Correlation-ID", corrId)
                .body(response);
    }

    @GetMapping("/api/v1/trips/{trip_id}")
    public ResponseEntity<TripResponse> getTrip(
            @PathVariable("trip_id") UUID tripId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {

        TripResponse response = tripService.getTrip(tripId);
        return ResponseEntity.ok()
                .header("X-Correlation-ID", correlationId != null ? correlationId : UUID.randomUUID().toString())
                .body(response);
    }

    @GetMapping("/api/v1/trips/me")
    public ResponseEntity<TripListResponse> listMyTrips(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-Role", required = false) String roleHeader,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {

        UUID userId = userIdHeader != null && !userIdHeader.isBlank()
                ? UUID.fromString(userIdHeader)
                : UUID.fromString("de28a2a8-33b1-4155-bfa1-76136eaece19"); // fallback demo

        String role = roleHeader != null ? roleHeader : "PASSENGER";
        TripListResponse response = tripService.listMyTrips(userId, role, limit);

        return ResponseEntity.ok()
                .header("X-Correlation-ID", correlationId != null ? correlationId : UUID.randomUUID().toString())
                .body(response);
    }

    @PostMapping("/api/v1/trips/{trip_id}/accept")
    public ResponseEntity<OperationResponse> acceptTrip(
            @PathVariable("trip_id") UUID tripId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "X-User-Id", required = false) String driverUserIdHeader,
            @RequestBody(required = false) TripActionRequest actionRequest) {

        UUID driverUserId = driverUserIdHeader != null ? UUID.fromString(driverUserIdHeader) : UUID.randomUUID();
        Long expectedVersion = actionRequest != null ? actionRequest.getExpected_version() : null;
        String corrId = correlationId != null ? correlationId : UUID.randomUUID().toString();

        OperationResponse op = tripService.acceptTrip(tripId, driverUserId, expectedVersion, idempotencyKey, corrId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/operations/" + op.getId()))
                .header("X-Correlation-ID", corrId)
                .body(op);
    }

    @PostMapping("/api/v1/trips/{trip_id}/arrive")
    public ResponseEntity<OperationResponse> arriveTrip(
            @PathVariable("trip_id") UUID tripId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "X-User-Id", required = false) String driverUserIdHeader,
            @RequestBody(required = false) TripActionRequest actionRequest) {

        UUID driverUserId = driverUserIdHeader != null ? UUID.fromString(driverUserIdHeader) : UUID.randomUUID();
        Long expectedVersion = actionRequest != null ? actionRequest.getExpected_version() : null;
        String corrId = correlationId != null ? correlationId : UUID.randomUUID().toString();

        OperationResponse op = tripService.arriveTrip(tripId, driverUserId, expectedVersion, idempotencyKey, corrId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/operations/" + op.getId()))
                .header("X-Correlation-ID", corrId)
                .body(op);
    }

    @PostMapping("/api/v1/trips/{trip_id}/start")
    public ResponseEntity<OperationResponse> startTrip(
            @PathVariable("trip_id") UUID tripId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "X-User-Id", required = false) String driverUserIdHeader,
            @RequestBody(required = false) TripActionRequest actionRequest) {

        UUID driverUserId = driverUserIdHeader != null ? UUID.fromString(driverUserIdHeader) : UUID.randomUUID();
        Long expectedVersion = actionRequest != null ? actionRequest.getExpected_version() : null;
        String corrId = correlationId != null ? correlationId : UUID.randomUUID().toString();

        OperationResponse op = tripService.startTrip(tripId, driverUserId, expectedVersion, idempotencyKey, corrId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/operations/" + op.getId()))
                .header("X-Correlation-ID", corrId)
                .body(op);
    }

    @PostMapping("/api/v1/trips/{trip_id}/complete")
    public ResponseEntity<OperationResponse> completeTrip(
            @PathVariable("trip_id") UUID tripId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "X-User-Id", required = false) String driverUserIdHeader,
            @RequestBody(required = false) TripActionRequest actionRequest) {

        UUID driverUserId = driverUserIdHeader != null ? UUID.fromString(driverUserIdHeader) : UUID.randomUUID();
        Long expectedVersion = actionRequest != null ? actionRequest.getExpected_version() : null;
        String corrId = correlationId != null ? correlationId : UUID.randomUUID().toString();

        OperationResponse op = tripService.completeTrip(tripId, driverUserId, expectedVersion, idempotencyKey, corrId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/operations/" + op.getId()))
                .header("X-Correlation-ID", corrId)
                .body(op);
    }

    @PostMapping("/api/v1/trips/{trip_id}/cancel")
    public ResponseEntity<OperationResponse> cancelTrip(
            @PathVariable("trip_id") UUID tripId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestBody(required = false) TripActionRequest actionRequest) {

        UUID callerUserId = userIdHeader != null ? UUID.fromString(userIdHeader) : UUID.randomUUID();
        Long expectedVersion = actionRequest != null ? actionRequest.getExpected_version() : null;
        String corrId = correlationId != null ? correlationId : UUID.randomUUID().toString();

        OperationResponse op = tripService.cancelTrip(tripId, callerUserId, "USER_CANCELLED", expectedVersion, idempotencyKey, corrId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/operations/" + op.getId()))
                .header("X-Correlation-ID", corrId)
                .body(op);
    }

    @PostMapping("/api/v1/trips/{trip_id}/reject")
    public ResponseEntity<OperationResponse> rejectTrip(
            @PathVariable("trip_id") UUID tripId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "X-User-Id", required = false) String driverUserIdHeader,
            @RequestBody(required = false) TripActionRequest actionRequest) {

        UUID driverUserId = driverUserIdHeader != null ? UUID.fromString(driverUserIdHeader) : UUID.randomUUID();
        Long expectedVersion = actionRequest != null ? actionRequest.getExpected_version() : null;
        String corrId = correlationId != null ? correlationId : UUID.randomUUID().toString();

        OperationResponse op = tripService.rejectTrip(tripId, driverUserId, expectedVersion, idempotencyKey, corrId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/operations/" + op.getId()))
                .header("X-Correlation-ID", corrId)
                .body(op);
    }

    @PostMapping("/api/v1/trips/{trip_id}/retry-payment")
    public ResponseEntity<OperationResponse> retryPayment(
            @PathVariable("trip_id") UUID tripId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "X-User-Id", required = false) String passengerIdHeader,
            @RequestBody(required = false) TripActionRequest actionRequest) {

        UUID passengerId = passengerIdHeader != null ? UUID.fromString(passengerIdHeader) : UUID.randomUUID();
        Long expectedVersion = actionRequest != null ? actionRequest.getExpected_version() : null;
        String corrId = correlationId != null ? correlationId : UUID.randomUUID().toString();

        OperationResponse op = tripService.retryPayment(tripId, passengerId, expectedVersion, idempotencyKey, corrId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/operations/" + op.getId()))
                .header("X-Correlation-ID", corrId)
                .body(op);
    }

    @GetMapping("/api/v1/operations/{operation_id}")
    public ResponseEntity<OperationResponse> getOperation(
            @PathVariable("operation_id") UUID operationId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {

        OperationResponse op = tripService.getOperation(operationId);
        return ResponseEntity.ok()
                .header("X-Correlation-ID", correlationId != null ? correlationId : UUID.randomUUID().toString())
                .body(op);
    }

    @GetMapping("/internal/trip/health/live")
    public ResponseEntity<Map<String, String>> live() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    @GetMapping("/internal/trip/health/ready")
    public ResponseEntity<Map<String, String>> ready() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
