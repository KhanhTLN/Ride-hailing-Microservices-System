package com.threecats.tripservice.grpc;
import com.ds03.contracts.common.v1.MutationContext;
import com.ds03.contracts.pricing.v1.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;
@Component @RequiredArgsConstructor
public class PricingServiceClient {
    private final PricingServiceGrpc.PricingServiceBlockingStub pricingStub;
    public Quote getQuote(String id,String passenger) {
        return pricingStub.withDeadlineAfter(3,TimeUnit.SECONDS).getQuote(GetQuoteRequest.newBuilder().setQuoteId(id).setPassengerId(passenger).build());
    }
    public FinalFare calculateFinalFare(String trip,String quote,String passenger,String key) {
        return pricingStub.withDeadlineAfter(3,TimeUnit.SECONDS).calculateFinalFare(CalculateFinalFareRequest.newBuilder().setTripId(trip).setQuoteId(quote).setPassengerId(passenger)
            .setMutation(MutationContext.newBuilder().setIdempotencyKey(key)).build());
    }
}
