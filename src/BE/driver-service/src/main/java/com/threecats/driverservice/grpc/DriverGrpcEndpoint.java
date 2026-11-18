package com.threecats.driverservice.grpc;

import com.ds03.contracts.common.v1.Point;
import com.ds03.contracts.common.v1.VehicleType;
import com.ds03.contracts.driver.v1.*;
import com.google.protobuf.Timestamp;
import com.threecats.common.exception.BaseException;
import com.threecats.common.exception.resource.ResourceNotFoundException;
import com.threecats.common.exception.validationAndRequest.InvalidInputException;
import com.threecats.driverservice.driver.repository.DriverRepository;
import com.threecats.driverservice.driver.service.DriverReservationService;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@GrpcService
public class DriverGrpcEndpoint extends DriverServiceGrpc.DriverServiceImplBase {
    private static final Logger LOG = LoggerFactory.getLogger(DriverGrpcEndpoint.class);

    private final DriverRepository drivers;
    private final DriverReservationService reservations;
    private final JdbcTemplate jdbc;

    public DriverGrpcEndpoint(DriverRepository drivers, DriverReservationService reservations, JdbcTemplate jdbc) {
        this.drivers = drivers;
        this.reservations = reservations;
        this.jdbc = jdbc;
    }

    @Override
    public void getDriver(GetDriverRequest request, StreamObserver<Driver> observer) {
        respond("GetDriver", observer, () -> {
            var driver = findDriver(request.getDriverId());
            AccountStatus status = switch (driver.getApprovalStatus()) {
                case APPROVED -> AccountStatus.APPROVED;
                case SUSPENDED, REJECTED -> AccountStatus.SUSPENDED;
                case PENDING -> AccountStatus.PENDING;
            };
            return Driver.newBuilder().setId(driver.getId().toString())
                    .setUserId(driver.getUserId().toString()).setAccountStatus(status)
                    .setAcceptingTrips(Boolean.TRUE.equals(driver.getAcceptingTrips())).build();
        });
    }

    @Override
    public void getDriverLocation(GetDriverLocationRequest request, StreamObserver<DriverLocation> observer) {
        respond("GetDriverLocation", observer, () -> {
            var driver = findDriver(request.getDriverId());
            if (driver.getLastLocationUpdatedAt() == null || driver.getLatitude() == null || driver.getLongitude() == null) {
                throw new ResourceNotFoundException("Driver location not found");
            }
            return DriverLocation.newBuilder().setDriverId(driver.getId().toString())
                    .setPoint(Point.newBuilder().setLatitude(driver.getLatitude()).setLongitude(driver.getLongitude()))
                    .setReceivedAt(timestamp(driver.getLastLocationUpdatedAt())).build();
        });
    }

    @Override
    public void findNearbyDrivers(FindNearbyDriversRequest request, StreamObserver<FindNearbyDriversResponse> observer) {
        respond("FindNearbyDrivers", observer, () -> {
            Point pickup = request.getPickup();
            VehicleType type = request.getVehicleType();
            if (!request.hasPickup() || !validPoint(pickup) || request.getRadiusM() < 100
                    || request.getRadiusM() > 10000 || request.getLimit() < 1 || request.getLimit() > 20
                    || request.getExcludedDriverIdsCount() > 100 || (type != VehicleType.CAR && type != VehicleType.MOTORBIKE)) {
                throw new InvalidInputException("Invalid driver search parameters");
            }
            Set<UUID> excluded = new HashSet<>();
            request.getExcludedDriverIdsList().forEach(id -> excluded.add(uuid(id)));
            var candidates = jdbc.query("""
                    SELECT d.driver_id, v.id AS vehicle_id, latitude, longitude, last_location_updated_at
                    FROM public.driver d JOIN public.vehicle v ON v.driver_id = d.driver_id AND v.is_active
                    WHERE d.approval_status = 'APPROVED' AND d.accepting_trips AND v.vehicle_type = ?
                      AND d.last_seen_at >= statement_timestamp() - interval '30 seconds'
                      AND d.last_location_updated_at >= statement_timestamp() - interval '30 seconds'
                      AND d.latitude IS NOT NULL AND d.longitude IS NOT NULL
                      AND NOT EXISTS (SELECT 1 FROM public.driver_reservation r WHERE r.driver_id = d.driver_id
                          AND (r.status = 'CONFIRMED' OR (r.status = 'HELD' AND r.expires_at > statement_timestamp())))
                    """, (row, index) -> new Candidate(row.getObject("driver_id", UUID.class),
                    row.getObject("vehicle_id", UUID.class), distance(pickup, row.getDouble("latitude"),
                    row.getDouble("longitude")), row.getObject("last_location_updated_at", OffsetDateTime.class)), type.name());
            return FindNearbyDriversResponse.newBuilder().addAllDrivers(candidates.stream()
                    .filter(c -> !excluded.contains(c.driver()) && c.distance() <= request.getRadiusM())
                    .sorted(Comparator.comparingDouble(Candidate::distance).thenComparing(c -> c.driver().toString()))
                    .limit(request.getLimit()).map(c -> NearbyDriver.newBuilder()
                            .setDriverId(c.driver().toString()).setVehicleId(c.vehicle().toString())
                            .setVehicleType(type).setDistanceM((int) Math.round(c.distance()))
                            .setLocationReceivedAt(timestamp(c.received())).build()).toList()).build();
        });
    }

    @Override
    public void reserveDriver(ReserveDriverRequest request, StreamObserver<Reservation> observer) {
        respond("ReserveDriver", observer, () -> toProto(reservations.reserve(DriverGrpcSecurity.CALLER.get(),
                request.getMutation().getIdempotencyKey(), uuid(request.getDriverId()), uuid(request.getTripId()),
                request.getHoldSeconds())));
    }

    @Override
    public void confirmReservation(ConfirmReservationRequest request, StreamObserver<Reservation> observer) {
        respond("ConfirmReservation", observer, () -> toProto(reservations.confirm(DriverGrpcSecurity.CALLER.get(),
                request.getMutation().getIdempotencyKey(), uuid(request.getReservationId()), uuid(request.getTripId()))));
    }

    @Override
    public void releaseReservation(ReleaseReservationRequest request, StreamObserver<Reservation> observer) {
        respond("ReleaseReservation", observer, () -> toProto(reservations.release(DriverGrpcSecurity.CALLER.get(),
                request.getMutation().getIdempotencyKey(), uuid(request.getReservationId()), uuid(request.getTripId()),
                request.getReason())));
    }

    @Override
    public void getReservation(GetReservationRequest request, StreamObserver<Reservation> observer) {
        respond("GetReservation", observer, () -> toProto(reservations.get(uuid(request.getReservationId()))));
    }

    private com.threecats.driverservice.driver.entity.Driver findDriver(String id) {
        return drivers.findByDriverId(uuid(id)).orElseThrow(() -> new ResourceNotFoundException("Driver not found"));
    }

    private static UUID uuid(String id) {
        if (!DriverGrpcSecurity.validUuid(id)) {
            throw new InvalidInputException("Expected a canonical UUID");
        }
        return UUID.fromString(id);
    }

    private static Timestamp timestamp(OffsetDateTime time) {
        return Timestamp.newBuilder().setSeconds(time.toEpochSecond()).setNanos(time.getNano()).build();
    }

    private static Reservation toProto(DriverReservationService.Reservation value) {
        return Reservation.newBuilder().setId(value.id().toString()).setTripId(value.tripId().toString())
                .setDriverId(value.driverId().toString()).setStatus(ReservationStatus.valueOf(value.status().name()))
                .setExpiresAt(timestamp(value.expiresAt())).setUpdatedAt(timestamp(value.updatedAt())).build();
    }

    private static boolean validPoint(Point p) {
        return Math.abs(p.getLatitude()) <= 90 && Math.abs(p.getLongitude()) <= 180;
    }

    private static double distance(Point pickup, double lat, double lon) {
        double dLat = Math.toRadians(lat - pickup.getLatitude());
        double dLon = Math.toRadians(lon - pickup.getLongitude());
        double a = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(Math.toRadians(pickup.getLatitude()))
                * Math.cos(Math.toRadians(lat)) * Math.pow(Math.sin(dLon / 2), 2);
        return 12_742_000 * Math.asin(Math.sqrt(Math.min(1, a)));
    }

    private static <T> void respond(String operation, StreamObserver<T> observer, Supplier<T> action) {
        String correlation = DriverGrpcSecurity.CORRELATION.get();
        try {
            observer.onNext(action.get());
            observer.onCompleted();
            LOG.info("operation={} correlation_id={} result=OK", operation, correlation);
        } catch (BaseException ex) {
            Status status = switch (ex.getErrorCode()) {
                case "INVALID_ARGUMENT" -> Status.INVALID_ARGUMENT;
                case "NOT_FOUND" -> Status.NOT_FOUND;
                case "FORBIDDEN" -> Status.PERMISSION_DENIED;
                case "IDEMPOTENCY_CONFLICT" -> Status.ALREADY_EXISTS;
                case "DRIVER_UNAVAILABLE", "RESERVATION_EXPIRED", "RESERVATION_MISMATCH" -> Status.FAILED_PRECONDITION;
                default -> Status.INTERNAL;
            };
            LOG.info("operation={} correlation_id={} result={}", operation, correlation, ex.getErrorCode());
            fail(observer, status.withDescription(ex.getMessage()), ex.getErrorCode(), correlation);
        } catch (RuntimeException ex) {
            LOG.error("operation={} correlation_id={} result=INTERNAL", operation, correlation, ex);
            fail(observer, Status.INTERNAL, "INTERNAL", correlation);
        }
    }

    private static void fail(StreamObserver<?> observer, Status status, String error, String correlation) {
        Metadata trailers = new Metadata();
        trailers.put(DriverGrpcSecurity.key("error-code"), error);
        if (correlation != null) {
            trailers.put(DriverGrpcSecurity.CORRELATION_HEADER, correlation);
        }
        observer.onError(status.asRuntimeException(trailers));
    }

    private record Candidate(UUID driver, UUID vehicle, double distance, OffsetDateTime received) {}
}
