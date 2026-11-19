package com.threecats.tripservice.grpc;

import com.ds03.contracts.common.v1.MutationContext;
import com.ds03.contracts.driver.v1.*;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class DriverServiceClient {

    private final DriverServiceGrpc.DriverServiceBlockingStub driverStub;

    public Optional<Driver> getDriver(String driverId) {
        try {
            return Optional.of(driverStub.getDriver(GetDriverRequest.newBuilder().setDriverId(driverId).build()));
        } catch (StatusRuntimeException e) {
            log.error("Failed to get driver {}: code={}, msg={}", driverId, e.getStatus().getCode(), e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<Reservation> confirmReservation(String reservationId, String tripId, String idempotencyKey) {
        try {
            ConfirmReservationRequest req = ConfirmReservationRequest.newBuilder()
                    .setReservationId(reservationId)
                    .setTripId(tripId)
                    .setMutation(MutationContext.newBuilder().setIdempotencyKey(idempotencyKey != null ? idempotencyKey : "").build())
                    .build();
            return Optional.of(driverStub.confirmReservation(req));
        } catch (StatusRuntimeException e) {
            log.error("Failed to confirm reservation {}: code={}, msg={}", reservationId, e.getStatus().getCode(), e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<Reservation> releaseReservation(String reservationId, String tripId, String reason, String idempotencyKey) {
        try {
            ReleaseReservationRequest req = ReleaseReservationRequest.newBuilder()
                    .setReservationId(reservationId)
                    .setTripId(tripId)
                    .setReason(reason != null ? reason : "Trip ended/cancelled")
                    .setMutation(MutationContext.newBuilder().setIdempotencyKey(idempotencyKey != null ? idempotencyKey : "").build())
                    .build();
            return Optional.of(driverStub.releaseReservation(req));
        } catch (StatusRuntimeException e) {
            log.error("Failed to release reservation {}: code={}, msg={}", reservationId, e.getStatus().getCode(), e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<DriverLocation> getDriverLocation(String driverId) {
        try {
            return Optional.of(driverStub.getDriverLocation(GetDriverLocationRequest.newBuilder().setDriverId(driverId).build()));
        } catch (StatusRuntimeException e) {
            log.error("Failed to get driver location for {}: code={}, msg={}", driverId, e.getStatus().getCode(), e.getMessage());
            return Optional.empty();
        }
    }
}
