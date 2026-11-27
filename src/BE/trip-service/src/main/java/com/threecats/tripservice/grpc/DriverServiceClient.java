package com.threecats.tripservice.grpc;
import com.ds03.contracts.common.v1.MutationContext;
import com.ds03.contracts.driver.v1.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;
@Component @RequiredArgsConstructor
public class DriverServiceClient {
    private final DriverServiceGrpc.DriverServiceBlockingStub driverStub;
    private DriverServiceGrpc.DriverServiceBlockingStub stub() { return driverStub.withDeadlineAfter(3,TimeUnit.SECONDS); }
    public Driver getDriver(String id) { return stub().getDriver(GetDriverRequest.newBuilder().setDriverId(id).build()); }
    public Reservation getReservation(String id) { return stub().getReservation(GetReservationRequest.newBuilder().setReservationId(id).build()); }
    public Reservation confirmReservation(String id,String trip,String key) {
        return stub().confirmReservation(ConfirmReservationRequest.newBuilder().setReservationId(id).setTripId(trip)
            .setMutation(MutationContext.newBuilder().setIdempotencyKey(key)).build());
    }
    public Reservation releaseReservation(String id,String trip,String reason,String key) {
        return stub().releaseReservation(ReleaseReservationRequest.newBuilder().setReservationId(id).setTripId(trip).setReason(reason)
            .setMutation(MutationContext.newBuilder().setIdempotencyKey(key)).build());
    }
}
