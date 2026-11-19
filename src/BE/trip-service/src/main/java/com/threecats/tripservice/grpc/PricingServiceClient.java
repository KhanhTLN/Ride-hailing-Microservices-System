package com.threecats.tripservice.grpc;

import com.ds03.contracts.common.v1.MutationContext;
import com.ds03.contracts.pricing.v1.*;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class PricingServiceClient {

    private final PricingServiceGrpc.PricingServiceBlockingStub pricingStub;

    public Optional<Quote> getQuote(String quoteId, String passengerId) {
        try {
            GetQuoteRequest req = GetQuoteRequest.newBuilder()
                    .setQuoteId(quoteId)
                    .setPassengerId(passengerId != null ? passengerId : "")
                    .build();
            return Optional.of(pricingStub.getQuote(req));
        } catch (StatusRuntimeException e) {
            log.error("Failed to get quote from PricingService: code={}, msg={}", e.getStatus().getCode(), e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<FinalFare> calculateFinalFare(String tripId, String quoteId, String passengerId, String idempotencyKey) {
        try {
            CalculateFinalFareRequest req = CalculateFinalFareRequest.newBuilder()
                    .setTripId(tripId)
                    .setQuoteId(quoteId)
                    .setPassengerId(passengerId != null ? passengerId : "")
                    .setMutation(MutationContext.newBuilder()
                            .setIdempotencyKey(idempotencyKey != null ? idempotencyKey : "")
                            .build())
                    .build();
            return Optional.of(pricingStub.calculateFinalFare(req));
        } catch (StatusRuntimeException e) {
            log.error("Failed to calculate final fare: code={}, msg={}", e.getStatus().getCode(), e.getMessage());
            return Optional.empty();
        }
    }
}
