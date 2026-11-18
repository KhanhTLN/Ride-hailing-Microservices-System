package com.threecats.driverservice.grpc;

import com.ds03.contracts.common.v1.Point;
import com.ds03.contracts.driver.v1.*;
import com.google.protobuf.Timestamp;
import com.threecats.driverservice.driver.entity.Driver;
import com.threecats.driverservice.driver.entity.Reservation;
import com.threecats.driverservice.driver.entity.Vehicle;
import com.threecats.driverservice.driver.repository.DriverRepository;
import com.threecats.driverservice.driver.repository.ReservationRepository;
import com.threecats.driverservice.driver.repository.VehicleRepository;
import com.threecats.driverservice.enums.DriverApprovalStatus;
import com.threecats.driverservice.enums.ReservationStatus;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DriverGrpcServiceImpl extends DriverServiceGrpc.DriverServiceImplBase {

    private final DriverRepository driverRepository;
    private final VehicleRepository vehicleRepository;
    private final ReservationRepository reservationRepository;

    private static final Metadata.Key<String> ERROR_CODE_KEY =
            Metadata.Key.of("error-code", Metadata.ASCII_STRING_MARSHALLER);

    private StatusRuntimeException buildError(Status status, String errorCode, String message) {
        Metadata metadata = new Metadata();
        metadata.put(ERROR_CODE_KEY, errorCode != null ? errorCode : status.getCode().name());
        return status.withDescription(message).asRuntimeException(metadata);
    }

    @Override
    public void getDriver(GetDriverRequest request, StreamObserver<com.ds03.contracts.driver.v1.Driver> responseObserver) {
        try {
            if (request.getDriverId().isBlank()) {
                responseObserver.onError(buildError(Status.INVALID_ARGUMENT, "INVALID_ARGUMENT", "driver_id is required"));
                return;
            }

            UUID driverUuid;
            try {
                driverUuid = UUID.fromString(request.getDriverId());
            } catch (IllegalArgumentException e) {
                responseObserver.onError(buildError(Status.INVALID_ARGUMENT, "INVALID_ARGUMENT", "Invalid driver_id UUID format"));
                return;
            }

            Driver driver = driverRepository.findByIdOrUserId(driverUuid)
                    .orElse(null);

            if (driver == null) {
                responseObserver.onError(buildError(Status.NOT_FOUND, "NOT_FOUND", "Driver not found: " + request.getDriverId()));
                return;
            }

            AccountStatus protoAccountStatus = AccountStatus.PENDING;
            if (driver.getApprovalStatus() == DriverApprovalStatus.APPROVED) {
                protoAccountStatus = AccountStatus.APPROVED;
            } else if (driver.getApprovalStatus() == DriverApprovalStatus.SUSPENDED) {
                protoAccountStatus = AccountStatus.SUSPENDED;
            }

            com.ds03.contracts.driver.v1.Driver response = com.ds03.contracts.driver.v1.Driver.newBuilder()
                    .setId(driver.getId().toString())
                    .setUserId(driver.getUserId().toString())
                    .setAccountStatus(protoAccountStatus)
                    .setAcceptingTrips(Boolean.TRUE.equals(driver.getAcceptingTrips()))
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception ex) {
            log.error("Error in getDriver RPC", ex);
            responseObserver.onError(buildError(Status.INTERNAL, "INTERNAL", ex.getMessage()));
        }
    }

    @Override
    public void getDriverLocation(GetDriverLocationRequest request, StreamObserver<DriverLocation> responseObserver) {
        try {
            if (request.getDriverId().isBlank()) {
                responseObserver.onError(buildError(Status.INVALID_ARGUMENT, "INVALID_ARGUMENT", "driver_id is required"));
                return;
            }

            UUID driverUuid;
            try {
                driverUuid = UUID.fromString(request.getDriverId());
            } catch (IllegalArgumentException e) {
                responseObserver.onError(buildError(Status.INVALID_ARGUMENT, "INVALID_ARGUMENT", "Invalid driver_id UUID format"));
                return;
            }

            Driver driver = driverRepository.findByIdOrUserId(driverUuid)
                    .orElse(null);

            if (driver == null) {
                responseObserver.onError(buildError(Status.NOT_FOUND, "NOT_FOUND", "Driver not found: " + request.getDriverId()));
                return;
            }

            if (driver.getLatitude() == null || driver.getLongitude() == null) {
                responseObserver.onError(buildError(Status.NOT_FOUND, "NOT_FOUND", "Driver location not available"));
                return;
            }

            Point point = Point.newBuilder()
                    .setLatitude(driver.getLatitude())
                    .setLongitude(driver.getLongitude())
                    .build();

            Instant receivedInstant = driver.getLastLocationUpdatedAt() != null
                    ? driver.getLastLocationUpdatedAt().toInstant()
                    : Instant.now();

            DriverLocation response = DriverLocation.newBuilder()
                    .setDriverId(driver.getId().toString())
                    .setPoint(point)
                    .setReceivedAt(toProtoTimestamp(receivedInstant))
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception ex) {
            log.error("Error in getDriverLocation RPC", ex);
            responseObserver.onError(buildError(Status.INTERNAL, "INTERNAL", ex.getMessage()));
        }
    }

    @Override
    @Transactional
    public void findNearbyDrivers(FindNearbyDriversRequest request, StreamObserver<FindNearbyDriversResponse> responseObserver) {
        try {
            Point pickup = request.getPickup();
            if (pickup == null || (pickup.getLatitude() == 0.0 && pickup.getLongitude() == 0.0)) {
                responseObserver.onError(buildError(Status.INVALID_ARGUMENT, "INVALID_ARGUMENT", "Valid pickup coordinates required"));
                return;
            }

            int radiusM = request.getRadiusM() > 0 ? request.getRadiusM() : 5000;
            radiusM = Math.max(100, Math.min(radiusM, 10000));

            int limit = request.getLimit() > 0 ? request.getLimit() : 10;
            limit = Math.max(1, Math.min(limit, 20));

            Set<String> excludedDriverIds = new HashSet<>(request.getExcludedDriverIdsList());

            // 1. Fetch all drivers who are APPROVED and accepting trips
            List<Driver> activeDrivers = driverRepository.findByApprovalStatusAndAcceptingTripsTrue(DriverApprovalStatus.APPROVED);

            record DriverCandidate(Driver driver, Vehicle vehicle, double distanceM) {}
            List<DriverCandidate> candidates = new ArrayList<>();

            for (Driver driver : activeDrivers) {
                String driverIdStr = driver.getId().toString();
                if (excludedDriverIds.contains(driverIdStr)) {
                    continue;
                }

                // Check location availability
                if (driver.getLatitude() == null || driver.getLongitude() == null) {
                    continue;
                }

                // Check active vehicle and vehicle type match
                Vehicle vehicle = vehicleRepository.findByDriverIdAndIsActiveTrue(driver.getId()).orElse(null);
                if (vehicle == null) {
                    continue;
                }

                if (request.getVehicleType() == com.ds03.contracts.common.v1.VehicleType.MOTORBIKE) {
                    if (!"MOTORBIKE".equalsIgnoreCase(vehicle.getVehicleType()) && !"BIKE".equalsIgnoreCase(vehicle.getVehicleType())) {
                        continue;
                    }
                } else if (request.getVehicleType() == com.ds03.contracts.common.v1.VehicleType.CAR) {
                    if (!"CAR".equalsIgnoreCase(vehicle.getVehicleType()) && !vehicle.getVehicleType().toUpperCase().contains("CAR")) {
                        continue;
                    }
                }

                // Check if driver already has active reservation (HELD or CONFIRMED)
                List<Reservation> activeReservations = reservationRepository.findByDriverIdAndStatusIn(
                        driver.getId(), List.of(ReservationStatus.HELD, ReservationStatus.CONFIRMED));

                boolean hasActiveRes = false;
                for (Reservation res : activeReservations) {
                    if (res.getStatus() == ReservationStatus.HELD && res.isExpired()) {
                        res.setStatus(ReservationStatus.EXPIRED);
                        reservationRepository.save(res);
                    } else {
                        hasActiveRes = true;
                        break;
                    }
                }
                if (hasActiveRes) {
                    continue;
                }

                // Calculate distance in meters
                double distance = calculateHaversineDistanceMeters(
                        pickup.getLatitude(), pickup.getLongitude(),
                        driver.getLatitude(), driver.getLongitude()
                );

                if (distance <= radiusM) {
                    candidates.add(new DriverCandidate(driver, vehicle, distance));
                }
            }

            // Sort: distance ASC, then driverId ASC
            candidates.sort(Comparator.comparingDouble(DriverCandidate::distanceM)
                    .thenComparing(c -> c.driver().getId()));

            FindNearbyDriversResponse.Builder responseBuilder = FindNearbyDriversResponse.newBuilder();
            int count = 0;
            for (DriverCandidate candidate : candidates) {
                if (count >= limit) break;

                Instant locInstant = candidate.driver().getLastLocationUpdatedAt() != null
                        ? candidate.driver().getLastLocationUpdatedAt().toInstant()
                        : Instant.now();

                NearbyDriver nearbyDriver = NearbyDriver.newBuilder()
                        .setDriverId(candidate.driver().getId().toString())
                        .setVehicleId(candidate.vehicle().getId().toString())
                        .setVehicleType(request.getVehicleType())
                        .setDistanceM((int) Math.round(candidate.distanceM()))
                        .setLocationReceivedAt(toProtoTimestamp(locInstant))
                        .build();

                responseBuilder.addDrivers(nearbyDriver);
                count++;
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        } catch (Exception ex) {
            log.error("Error in findNearbyDrivers RPC", ex);
            responseObserver.onError(buildError(Status.INTERNAL, "INTERNAL", ex.getMessage()));
        }
    }

    @Override
    @Transactional
    public void reserveDriver(ReserveDriverRequest request, StreamObserver<com.ds03.contracts.driver.v1.Reservation> responseObserver) {
        try {
            String idempotencyKey = request.getMutation() != null ? request.getMutation().getIdempotencyKey() : "";
            if (!idempotencyKey.isBlank()) {
                Optional<Reservation> existing = reservationRepository.findByIdempotencyKey(idempotencyKey);
                if (existing.isPresent()) {
                    log.info("Idempotent hit for reserveDriver key: {}", idempotencyKey);
                    responseObserver.onNext(toProtoReservation(existing.get()));
                    responseObserver.onCompleted();
                    return;
                }
            }

            if (request.getDriverId().isBlank() || request.getTripId().isBlank()) {
                responseObserver.onError(buildError(Status.INVALID_ARGUMENT, "INVALID_ARGUMENT", "driver_id and trip_id are required"));
                return;
            }

            UUID driverUuid;
            UUID tripUuid;
            try {
                driverUuid = UUID.fromString(request.getDriverId());
                tripUuid = UUID.fromString(request.getTripId());
            } catch (IllegalArgumentException e) {
                responseObserver.onError(buildError(Status.INVALID_ARGUMENT, "INVALID_ARGUMENT", "Invalid UUID format"));
                return;
            }

            Driver driver = driverRepository.findByIdOrUserId(driverUuid).orElse(null);
            if (driver == null) {
                responseObserver.onError(buildError(Status.NOT_FOUND, "NOT_FOUND", "Driver not found: " + request.getDriverId()));
                return;
            }

            // Re-check driver eligibility
            if (driver.getApprovalStatus() != DriverApprovalStatus.APPROVED || !Boolean.TRUE.equals(driver.getAcceptingTrips())) {
                responseObserver.onError(buildError(Status.FAILED_PRECONDITION, "DRIVER_UNAVAILABLE", "Driver is not available or not accepting trips"));
                return;
            }

            // Check if driver has active reservation
            List<Reservation> driverReservations = reservationRepository.findByDriverIdAndStatusIn(
                    driver.getId(), List.of(ReservationStatus.HELD, ReservationStatus.CONFIRMED));

            for (Reservation res : driverReservations) {
                if (res.getStatus() == ReservationStatus.HELD && res.isExpired()) {
                    res.setStatus(ReservationStatus.EXPIRED);
                    reservationRepository.save(res);
                } else {
                    responseObserver.onError(buildError(Status.FAILED_PRECONDITION, "DRIVER_UNAVAILABLE", "Driver already has an active reservation"));
                    return;
                }
            }

            // Check if trip already has active reservation
            List<Reservation> tripReservations = reservationRepository.findByTripIdAndStatusIn(
                    tripUuid, List.of(ReservationStatus.HELD, ReservationStatus.CONFIRMED));

            for (Reservation res : tripReservations) {
                if (res.getStatus() == ReservationStatus.HELD && res.isExpired()) {
                    res.setStatus(ReservationStatus.EXPIRED);
                    reservationRepository.save(res);
                } else {
                    responseObserver.onError(buildError(Status.FAILED_PRECONDITION, "OPERATION_IN_PROGRESS", "Trip already has an active reservation"));
                    return;
                }
            }

            int holdSeconds = request.getHoldSeconds() > 0 ? request.getHoldSeconds() : 30;
            holdSeconds = Math.max(15, Math.min(holdSeconds, 60));

            Reservation reservation = Reservation.builder()
                    .driverId(driver.getId())
                    .tripId(tripUuid)
                    .status(ReservationStatus.HELD)
                    .holdSeconds(holdSeconds)
                    .caller("matching")
                    .idempotencyKey(idempotencyKey.isBlank() ? null : idempotencyKey)
                    .expiresAt(OffsetDateTime.now().plusSeconds(holdSeconds))
                    .build();

            reservation = reservationRepository.save(reservation);
            log.info("Driver reserved successfully: reservationId={}, driverId={}, tripId={}", reservation.getId(), driver.getId(), tripUuid);

            responseObserver.onNext(toProtoReservation(reservation));
            responseObserver.onCompleted();
        } catch (Exception ex) {
            log.error("Error in reserveDriver RPC", ex);
            responseObserver.onError(buildError(Status.INTERNAL, "INTERNAL", ex.getMessage()));
        }
    }

    @Override
    @Transactional
    public void confirmReservation(ConfirmReservationRequest request, StreamObserver<com.ds03.contracts.driver.v1.Reservation> responseObserver) {
        try {
            if (request.getReservationId().isBlank() || request.getTripId().isBlank()) {
                responseObserver.onError(buildError(Status.INVALID_ARGUMENT, "INVALID_ARGUMENT", "reservation_id and trip_id are required"));
                return;
            }

            UUID resUuid;
            UUID tripUuid;
            try {
                resUuid = UUID.fromString(request.getReservationId());
                tripUuid = UUID.fromString(request.getTripId());
            } catch (IllegalArgumentException e) {
                responseObserver.onError(buildError(Status.INVALID_ARGUMENT, "INVALID_ARGUMENT", "Invalid UUID format"));
                return;
            }

            Reservation reservation = reservationRepository.findById(resUuid).orElse(null);
            if (reservation == null) {
                responseObserver.onError(buildError(Status.NOT_FOUND, "NOT_FOUND", "Reservation not found: " + request.getReservationId()));
                return;
            }

            if (!reservation.getTripId().equals(tripUuid)) {
                responseObserver.onError(buildError(Status.FAILED_PRECONDITION, "RESERVATION_MISMATCH", "Reservation does not match trip_id"));
                return;
            }

            if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
                // Idempotent success
                responseObserver.onNext(toProtoReservation(reservation));
                responseObserver.onCompleted();
                return;
            }

            if (reservation.getStatus() == ReservationStatus.RELEASED || reservation.getStatus() == ReservationStatus.EXPIRED) {
                responseObserver.onError(buildError(Status.FAILED_PRECONDITION, "RESERVATION_EXPIRED", "Cannot confirm a RELEASED or EXPIRED reservation"));
                return;
            }

            if (reservation.getStatus() == ReservationStatus.HELD) {
                if (reservation.isExpired()) {
                    reservation.setStatus(ReservationStatus.EXPIRED);
                    reservationRepository.save(reservation);
                    responseObserver.onError(buildError(Status.FAILED_PRECONDITION, "RESERVATION_EXPIRED", "Reservation hold time has expired"));
                    return;
                }

                reservation.setStatus(ReservationStatus.CONFIRMED);
                reservation = reservationRepository.save(reservation);
                log.info("Reservation confirmed: id={}, driverId={}, tripId={}", reservation.getId(), reservation.getDriverId(), tripUuid);

                responseObserver.onNext(toProtoReservation(reservation));
                responseObserver.onCompleted();
            }
        } catch (Exception ex) {
            log.error("Error in confirmReservation RPC", ex);
            responseObserver.onError(buildError(Status.INTERNAL, "INTERNAL", ex.getMessage()));
        }
    }

    @Override
    @Transactional
    public void releaseReservation(ReleaseReservationRequest request, StreamObserver<com.ds03.contracts.driver.v1.Reservation> responseObserver) {
        try {
            if (request.getReservationId().isBlank() || request.getTripId().isBlank()) {
                responseObserver.onError(buildError(Status.INVALID_ARGUMENT, "INVALID_ARGUMENT", "reservation_id and trip_id are required"));
                return;
            }

            UUID resUuid;
            UUID tripUuid;
            try {
                resUuid = UUID.fromString(request.getReservationId());
                tripUuid = UUID.fromString(request.getTripId());
            } catch (IllegalArgumentException e) {
                responseObserver.onError(buildError(Status.INVALID_ARGUMENT, "INVALID_ARGUMENT", "Invalid UUID format"));
                return;
            }

            Reservation reservation = reservationRepository.findById(resUuid).orElse(null);
            if (reservation == null) {
                responseObserver.onError(buildError(Status.NOT_FOUND, "NOT_FOUND", "Reservation not found: " + request.getReservationId()));
                return;
            }

            if (!reservation.getTripId().equals(tripUuid)) {
                responseObserver.onError(buildError(Status.FAILED_PRECONDITION, "RESERVATION_MISMATCH", "Reservation does not match trip_id"));
                return;
            }

            // Terminal state returns terminal success
            if (reservation.getStatus() == ReservationStatus.RELEASED || reservation.getStatus() == ReservationStatus.EXPIRED) {
                responseObserver.onNext(toProtoReservation(reservation));
                responseObserver.onCompleted();
                return;
            }

            reservation.setStatus(ReservationStatus.RELEASED);
            if (!request.getReason().isBlank()) {
                reservation.setReason(request.getReason());
            }
            reservation = reservationRepository.save(reservation);
            log.info("Reservation released: id={}, driverId={}, reason={}", reservation.getId(), reservation.getDriverId(), request.getReason());

            responseObserver.onNext(toProtoReservation(reservation));
            responseObserver.onCompleted();
        } catch (Exception ex) {
            log.error("Error in releaseReservation RPC", ex);
            responseObserver.onError(buildError(Status.INTERNAL, "INTERNAL", ex.getMessage()));
        }
    }

    @Override
    @Transactional
    public void getReservation(GetReservationRequest request, StreamObserver<com.ds03.contracts.driver.v1.Reservation> responseObserver) {
        try {
            if (request.getReservationId().isBlank()) {
                responseObserver.onError(buildError(Status.INVALID_ARGUMENT, "INVALID_ARGUMENT", "reservation_id is required"));
                return;
            }

            UUID resUuid;
            try {
                resUuid = UUID.fromString(request.getReservationId());
            } catch (IllegalArgumentException e) {
                responseObserver.onError(buildError(Status.INVALID_ARGUMENT, "INVALID_ARGUMENT", "Invalid reservation_id UUID format"));
                return;
            }

            Reservation reservation = reservationRepository.findById(resUuid).orElse(null);
            if (reservation == null) {
                responseObserver.onError(buildError(Status.NOT_FOUND, "NOT_FOUND", "Reservation not found: " + request.getReservationId()));
                return;
            }

            // If HELD and expired, update state to EXPIRED
            if (reservation.getStatus() == ReservationStatus.HELD && reservation.isExpired()) {
                reservation.setStatus(ReservationStatus.EXPIRED);
                reservation = reservationRepository.save(reservation);
            }

            responseObserver.onNext(toProtoReservation(reservation));
            responseObserver.onCompleted();
        } catch (Exception ex) {
            log.error("Error in getReservation RPC", ex);
            responseObserver.onError(buildError(Status.INTERNAL, "INTERNAL", ex.getMessage()));
        }
    }

    private com.ds03.contracts.driver.v1.Reservation toProtoReservation(Reservation res) {
        com.ds03.contracts.driver.v1.ReservationStatus protoStatus;
        switch (res.getStatus()) {
            case HELD -> protoStatus = com.ds03.contracts.driver.v1.ReservationStatus.HELD;
            case CONFIRMED -> protoStatus = com.ds03.contracts.driver.v1.ReservationStatus.CONFIRMED;
            case RELEASED -> protoStatus = com.ds03.contracts.driver.v1.ReservationStatus.RELEASED;
            case EXPIRED -> protoStatus = com.ds03.contracts.driver.v1.ReservationStatus.EXPIRED;
            default -> protoStatus = com.ds03.contracts.driver.v1.ReservationStatus.RESERVATION_STATUS_UNSPECIFIED;
        }

        Instant expiresInstant = res.getExpiresAt() != null ? res.getExpiresAt().toInstant() : Instant.now();
        Instant updatedInstant = res.getUpdatedAt() != null ? res.getUpdatedAt().toInstant() : (res.getCreatedAt() != null ? res.getCreatedAt().toInstant() : Instant.now());

        return com.ds03.contracts.driver.v1.Reservation.newBuilder()
                .setId(res.getId().toString())
                .setDriverId(res.getDriverId().toString())
                .setTripId(res.getTripId().toString())
                .setStatus(protoStatus)
                .setExpiresAt(toProtoTimestamp(expiresInstant))
                .setUpdatedAt(toProtoTimestamp(updatedInstant))
                .build();
    }

    private static Timestamp toProtoTimestamp(Instant instant) {
        if (instant == null) {
            instant = Instant.now();
        }
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static double calculateHaversineDistanceMeters(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
