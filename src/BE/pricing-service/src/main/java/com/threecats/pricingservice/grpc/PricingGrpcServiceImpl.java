package com.threecats.pricingservice.grpc;

import com.ds03.contracts.common.v1.Money;
import com.ds03.contracts.common.v1.Point;
import com.ds03.contracts.common.v1.VehicleType;
import com.ds03.contracts.pricing.v1.CalculateFinalFareRequest;
import com.ds03.contracts.pricing.v1.FinalFare;
import com.ds03.contracts.pricing.v1.GetQuoteRequest;
import com.ds03.contracts.pricing.v1.PricingServiceGrpc;
import com.ds03.contracts.pricing.v1.Quote;
import com.google.protobuf.Timestamp;
import com.threecats.pricingservice.model.QuoteRecord;
import com.threecats.pricingservice.service.QuoteStore;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class PricingGrpcServiceImpl extends PricingServiceGrpc.PricingServiceImplBase {

    private final QuoteStore quoteStore;

    @Override
    public void getQuote(GetQuoteRequest request, StreamObserver<Quote> responseObserver) {
        String quoteId = request.getQuoteId();
        String passengerId = request.getPassengerId();

        log.info("gRPC GetQuote: quoteId={}, passengerId={}", quoteId, passengerId);

        var quoteOpt = quoteStore.findById(quoteId);
        if (quoteOpt.isEmpty()) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Quote not found with id: " + quoteId)
                    .asRuntimeException());
            return;
        }

        QuoteRecord quoteRecord = quoteOpt.get();

        // Check if passenger matches (if passengerId is provided in request)
        if (passengerId != null && !passengerId.isBlank()
                && !passengerId.equals(quoteRecord.getPassengerId())) {
            responseObserver.onError(Status.PERMISSION_DENIED
                    .withDescription("Passenger does not match quote owner")
                    .asRuntimeException());
            return;
        }

        // Check if quote is expired
        if (quoteRecord.getExpiresAt().isBefore(Instant.now())) {
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription("Quote expired at " + quoteRecord.getExpiresAt())
                    .asRuntimeException());
            return;
        }

        VehicleType protoVehicleType = "CAR".equalsIgnoreCase(quoteRecord.getVehicleType())
                ? VehicleType.CAR
                : VehicleType.MOTORBIKE;

        Timestamp expiresAtTimestamp = Timestamp.newBuilder()
                .setSeconds(quoteRecord.getExpiresAt().getEpochSecond())
                .setNanos(quoteRecord.getExpiresAt().getNano())
                .build();

        Quote.Builder builder = Quote.newBuilder()
                .setId(quoteRecord.getId())
                .setPassengerId(quoteRecord.getPassengerId())
                .setPickup(Point.newBuilder()
                        .setLatitude(quoteRecord.getPickup().getLatitude())
                        .setLongitude(quoteRecord.getPickup().getLongitude())
                        .setAddress(quoteRecord.getPickup().getAddress() != null ? quoteRecord.getPickup().getAddress() : "")
                        .build())
                .setDropoff(Point.newBuilder()
                        .setLatitude(quoteRecord.getDropoff().getLatitude())
                        .setLongitude(quoteRecord.getDropoff().getLongitude())
                        .setAddress(quoteRecord.getDropoff().getAddress() != null ? quoteRecord.getDropoff().getAddress() : "")
                        .build())
                .setVehicleType(protoVehicleType)
                .setEstimatedDistanceM((int) (long) quoteRecord.getEstimatedDistanceM())
                .setEstimatedDurationS((int) (long) quoteRecord.getEstimatedDurationS())
                .setFare(Money.newBuilder()
                        .setAmount(quoteRecord.getFare().getAmount())
                        .setCurrency(quoteRecord.getFare().getCurrency())
                        .build())
                .setPricingVersion(quoteRecord.getPricingVersion())
                .setExpiresAt(expiresAtTimestamp);

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void calculateFinalFare(CalculateFinalFareRequest request, StreamObserver<FinalFare> responseObserver) {
        String tripId = request.getTripId();
        String quoteId = request.getQuoteId();
        log.info("gRPC CalculateFinalFare: tripId={}, quoteId={}", tripId, quoteId);

        var quoteOpt = quoteStore.findById(quoteId);
        if (quoteOpt.isEmpty()) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Quote not found with id: " + quoteId)
                    .asRuntimeException());
            return;
        }

        QuoteRecord quoteRecord = quoteOpt.get();

        // Contract v1 rule: final fare equals accepted quote fare, even after quote expiry.
        FinalFare finalFare = FinalFare.newBuilder()
                .setTripId(tripId)
                .setQuoteId(quoteId)
                .setFare(Money.newBuilder()
                        .setAmount(quoteRecord.getFare().getAmount())
                        .setCurrency(quoteRecord.getFare().getCurrency())
                        .build())
                .setPricingVersion(quoteRecord.getPricingVersion())
                .build();

        responseObserver.onNext(finalFare);
        responseObserver.onCompleted();
    }
}
