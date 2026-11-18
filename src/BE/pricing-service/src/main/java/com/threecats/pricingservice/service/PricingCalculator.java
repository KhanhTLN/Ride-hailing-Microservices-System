package com.threecats.pricingservice.service;

import com.threecats.pricingservice.dto.MoneyDto;
import com.threecats.pricingservice.dto.PointDto;
import com.threecats.pricingservice.dto.QuoteInputRequest;
import com.threecats.pricingservice.dto.QuoteResponse;
import com.threecats.pricingservice.model.QuoteRecord;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class PricingCalculator {
    public static final String PRICING_VERSION = "v1.0.0";
    public static final long EXPIRATION_SECONDS = 120; // 120s immutable expiry

    private final QuoteStore quoteStore;

    public PricingCalculator(QuoteStore quoteStore) {
        this.quoteStore = quoteStore;
    }

    public QuoteRecord calculateQuote(QuoteInputRequest request, String passengerId, String idempotencyKey) {
        // Check idempotency key first
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = quoteStore.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        PointDto pickup = request.getPickup();
        PointDto dropoff = request.getDropoff();
        String vehicleType = request.getVehicle_type().toUpperCase();

        // 1. Calculate Haversine straight-line distance in meters
        double straightDistanceMeters = calculateHaversineMeters(
                pickup.getLatitude(), pickup.getLongitude(),
                dropoff.getLatitude(), dropoff.getLongitude()
        );

        // 2. Road factor 1.3 rounded up to meters
        long estimatedDistanceM = (long) Math.ceil(straightDistanceMeters * 1.3);
        if (estimatedDistanceM < 100) {
            estimatedDistanceM = 100; // minimum distance
        }

        // 3. Simulated speed 25 km/h => duration in seconds
        long estimatedDurationS = (long) Math.ceil(estimatedDistanceM * 3.6 / 25.0);

        // 4. Calculate fare
        double distanceKm = estimatedDistanceM / 1000.0;
        double rawFare;
        if ("CAR".equalsIgnoreCase(vehicleType)) {
            rawFare = 20000.0 + (10000.0 * distanceKm);
        } else {
            // MOTORBIKE
            rawFare = 10000.0 + (5000.0 * distanceKm);
        }

        // Round up to nearest 1000 VND
        long roundedFareVnd = (long) (Math.ceil(rawFare / 1000.0) * 1000);

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(EXPIRATION_SECONDS);

        QuoteRecord quote = QuoteRecord.builder()
                .id(UUID.randomUUID().toString())
                .passengerId(passengerId != null && !passengerId.isBlank() ? passengerId : UUID.randomUUID().toString())
                .pickup(pickup)
                .dropoff(dropoff)
                .vehicleType(vehicleType)
                .estimatedDistanceM(estimatedDistanceM)
                .estimatedDurationS(estimatedDurationS)
                .fare(new MoneyDto(String.valueOf(roundedFareVnd), "VND"))
                .pricingVersion(PRICING_VERSION)
                .expiresAt(expiresAt)
                .createdAt(now)
                .build();

        quoteStore.save(idempotencyKey, quote);
        return quote;
    }

    public static double calculateHaversineMeters(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000.0; // Radius of Earth in meters
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaPhi = Math.toRadians(lat2 - lat1);
        double deltaLambda = Math.toRadians(lon2 - lon1);

        double a = Math.sin(deltaPhi / 2.0) * Math.sin(deltaPhi / 2.0) +
                Math.cos(phi1) * Math.cos(phi2) *
                Math.sin(deltaLambda / 2.0) * Math.sin(deltaLambda / 2.0);

        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return R * c;
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
