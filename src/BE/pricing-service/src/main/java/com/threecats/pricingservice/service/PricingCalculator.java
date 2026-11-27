package com.threecats.pricingservice.service;

import com.threecats.pricingservice.dto.MoneyDto;
import com.threecats.pricingservice.dto.QuoteInputRequest;
import com.threecats.pricingservice.dto.QuoteResponse;
import com.threecats.pricingservice.maps.RoutingService;
import com.threecats.pricingservice.model.QuoteRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.UUID;

@Service
public class PricingCalculator {
    public static final String PRICING_VERSION = "osrm-v1";
    public static final long EXPIRATION_SECONDS = 120;
    private final QuoteStore quoteStore;
    private final RoutingService routing;
    private final TransactionTemplate transaction;

    public PricingCalculator(QuoteStore quoteStore, RoutingService routing, PlatformTransactionManager manager) {
        this.quoteStore=quoteStore;
        this.routing=routing;
        this.transaction=new TransactionTemplate(manager);
    }

    public QuoteRecord calculateQuote(QuoteInputRequest request,String passengerId,String idempotencyKey) {
        if(idempotencyKey==null || idempotencyKey.isBlank() || idempotencyKey.length()>128)
            throw new com.threecats.common.exception.validationAndRequest.InvalidInputException("Idempotency-Key is required");
        String key=passengerId+":"+idempotencyKey;
        var existing=quoteStore.findByIdempotencyKey(key);
        if(existing.isPresent()) return replay(existing.get(),request,passengerId);

        // External I/O is outside the transaction. Client-provided distances/fare are never used.
        var route=routing.route(request.getPickup(),request.getDropoff(),request.getVehicle_type());
        return transaction.execute(status -> {
            quoteStore.lockKey(key);
            var concurrent=quoteStore.findByIdempotencyKey(key);
            if(concurrent.isPresent()) return replay(concurrent.get(),request,passengerId);
            double distanceKm=route.distance_m()/1000.0;
            double rawFare="CAR".equals(request.getVehicle_type()) ? 20000+10000*distanceKm : 10000+5000*distanceKm;
            long fare=(long)(Math.ceil(rawFare/1000.0)*1000);
            Instant now=Instant.now();
            QuoteRecord quote=QuoteRecord.builder().id(UUID.randomUUID().toString()).passengerId(passengerId)
                .pickup(request.getPickup()).dropoff(request.getDropoff()).vehicleType(request.getVehicle_type())
                .estimatedDistanceM(route.distance_m()).estimatedDurationS(route.duration_s())
                .fare(new MoneyDto(String.valueOf(fare),"VND")).pricingVersion(PRICING_VERSION+"-"+route.profile())
                .expiresAt(now.plusSeconds(EXPIRATION_SECONDS)).createdAt(now).build();
            quoteStore.save(key,quote);
            return quote;
        });
    }
    private QuoteRecord replay(QuoteRecord quote,QuoteInputRequest request,String passengerId) {
        if(!quote.getPassengerId().equals(passengerId) || !quote.getVehicleType().equals(request.getVehicle_type())
            || !java.util.Objects.equals(quote.getPickup(),request.getPickup()) || !java.util.Objects.equals(quote.getDropoff(),request.getDropoff()))
            throw new com.threecats.common.exception.BaseException(409,"IDEMPOTENCY_CONFLICT","Idempotency key was used with a different request") {};
        return quote;
    }

    public QuoteResponse toResponse(QuoteRecord record) {
        return QuoteResponse.builder()
                .id(record.getId())
                .passenger_id(record.getPassengerId())
                .pickup(record.getPickup())
                .dropoff(record.getDropoff())
                .vehicle_type(record.getVehicleType())
                .estimated_distance_m(record.getEstimatedDistanceM())
                .estimated_duration_s(record.getEstimatedDurationS())
                .fare(record.getFare())
                .pricing_version(record.getPricingVersion())
                .expires_at(record.getExpiresAt().toString())
                .build();
    }
}
