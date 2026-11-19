package com.threecats.tripservice.service;

import com.ds03.contracts.driver.v1.Driver;
import com.ds03.contracts.pricing.v1.FinalFare;
import com.ds03.contracts.pricing.v1.Quote;
import com.threecats.tripservice.dto.*;
import com.threecats.tripservice.entity.Trip;
import com.threecats.tripservice.entity.TripOperation;
import com.threecats.tripservice.entity.enums.OperationStatus;
import com.threecats.tripservice.entity.enums.OperationType;
import com.threecats.tripservice.entity.enums.PaymentStatus;
import com.threecats.tripservice.entity.enums.TripStatus;
import com.threecats.tripservice.exception.TripApiException;
import com.threecats.tripservice.grpc.DriverServiceClient;
import com.threecats.tripservice.grpc.PricingServiceClient;
import com.threecats.tripservice.repository.TripOperationRepository;
import com.threecats.tripservice.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TripService {

    public static final String TOPIC_TRIP_EVENTS = "ds03.trip.events.v1";
    public static final String TOPIC_PAYMENT_COMMANDS = "ds03.payment.commands.v1";

    private final TripRepository tripRepository;
    private final TripOperationRepository tripOperationRepository;
    private final OutboxService outboxService;
    private final PricingServiceClient pricingServiceClient;
    private final DriverServiceClient driverServiceClient;

    private static final List<TripStatus> TERMINAL_STATUSES = List.of(
            TripStatus.COMPLETED,
            TripStatus.CANCELLED,
            TripStatus.NO_DRIVER
    );

    @Transactional
    public TripResponse createTrip(TripCreateRequest request, UUID passengerId, String idempotencyKey, String correlationId) {
        log.info("Creating trip for passenger={}, quote={}", passengerId, request.getQuote_id());

        // 1. Check active trip constraint for passenger
        if (tripRepository.existsByPassengerIdAndStatusNotIn(passengerId, TERMINAL_STATUSES)) {
            throw new TripApiException(HttpStatus.CONFLICT, "ACTIVE_TRIP_EXISTS", "Passenger already has an active trip");
        }

        // 2. Check unique quote constraint
        if (tripRepository.findByQuoteId(request.getQuote_id()).isPresent()) {
            throw new TripApiException(HttpStatus.CONFLICT, "QUOTE_ALREADY_USED", "Quote has already been used for another trip");
        }

        // 3. Call Pricing gRPC to validate quote
        Optional<Quote> quoteOpt = pricingServiceClient.getQuote(request.getQuote_id().toString(), passengerId.toString());
        if (quoteOpt.isEmpty()) {
            throw new TripApiException(HttpStatus.BAD_REQUEST, "INVALID_QUOTE", "Quote is invalid, expired or not found");
        }
        Quote quote = quoteOpt.get();

        // 4. Create Trip entity
        UUID tripId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();

        Trip trip = Trip.builder()
                .id(tripId)
                .passengerId(passengerId)
                .quoteId(request.getQuote_id())
                .status(TripStatus.SEARCHING)
                .currentAttemptId(attemptId)
                .searchBudgetSeconds(120)
                .pickupLatitude(quote.getPickup().getLatitude())
                .pickupLongitude(quote.getPickup().getLongitude())
                .pickupAddress(quote.getPickup().getAddress())
                .dropoffLatitude(quote.getDropoff().getLatitude())
                .dropoffLongitude(quote.getDropoff().getLongitude())
                .dropoffAddress(quote.getDropoff().getAddress())
                .vehicleType(quote.getVehicleType().name())
                .fareAmount(new BigDecimal(quote.getFare().getAmount()))
                .fareCurrency(quote.getFare().getCurrency())
                .paymentStatus(PaymentStatus.PENDING)
                .attemptNumber(1)
                .version(0L)
                .build();

        trip = tripRepository.save(trip);

        // 5. Record Outbox Event: TripRequested
        Map<String, Object> pickupMap = Map.of(
                "latitude", trip.getPickupLatitude(),
                "longitude", trip.getPickupLongitude(),
                "address", trip.getPickupAddress() != null ? trip.getPickupAddress() : ""
        );
        Map<String, Object> dropoffMap = Map.of(
                "latitude", trip.getDropoffLatitude(),
                "longitude", trip.getDropoffLongitude(),
                "address", trip.getDropoffAddress() != null ? trip.getDropoffAddress() : ""
        );
        Map<String, Object> tripRequestedData = Map.of(
                "trip_id", trip.getId().toString(),
                "attempt_id", attemptId.toString(),
                "passenger_id", passengerId.toString(),
                "pickup", pickupMap,
                "dropoff", dropoffMap,
                "vehicle_type", trip.getVehicleType(),
                "search_budget_seconds", 120
        );

        outboxService.recordEvent(TOPIC_TRIP_EVENTS, "TripRequested", trip.getId(), 1, correlationId, tripRequestedData);

        return toResponse(trip);
    }

    public TripResponse getTrip(UUID tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripApiException(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", "Trip not found with id: " + tripId));
        return toResponse(trip);
    }

    public TripListResponse listMyTrips(UUID userId, String role, int limit) {
        int pageSize = Math.min(Math.max(limit, 1), 100);
        List<Trip> trips;
        if ("DRIVER".equalsIgnoreCase(role)) {
            trips = tripRepository.findByDriverUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, pageSize));
        } else {
            trips = tripRepository.findByPassengerIdOrderByCreatedAtDesc(userId, PageRequest.of(0, pageSize));
        }

        List<TripResponse> items = trips.stream().map(this::toResponse).toList();
        return TripListResponse.builder().items(items).build();
    }

    public OperationResponse getOperation(UUID operationId) {
        TripOperation op = tripOperationRepository.findById(operationId)
                .orElseThrow(() -> new TripApiException(HttpStatus.NOT_FOUND, "OPERATION_NOT_FOUND", "Operation not found"));
        return toOperationResponse(op);
    }

    // =========================================================================
    // ASYNC MUTATION ACTIONS (ACCEPT, ARRIVE, START, COMPLETE, CANCEL, REJECT, RETRY_PAYMENT)
    // =========================================================================

    @Transactional
    public OperationResponse acceptTrip(UUID tripId, UUID driverUserId, Long expectedVersion, String idempotencyKey, String correlationId) {
        return executeOperation(tripId, OperationType.ACCEPT, idempotencyKey, correlationId, expectedVersion, trip -> {
            if (trip.getStatus() != TripStatus.DRIVER_OFFERED && trip.getStatus() != TripStatus.SEARCHING) {
                throw new TripApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "Trip cannot be accepted in state: " + trip.getStatus());
            }

            trip.setStatus(TripStatus.ACCEPTED);
            tripRepository.save(trip);

            Map<String, Object> data = Map.of(
                    "trip_id", trip.getId().toString(),
                    "driver_id", trip.getDriverId() != null ? trip.getDriverId().toString() : UUID.randomUUID().toString(),
                    "attempt_id", trip.getCurrentAttemptId().toString()
            );
            outboxService.recordEvent(TOPIC_TRIP_EVENTS, "TripAccepted", trip.getId(), trip.getVersion().intValue(), correlationId, data);
        });
    }

    @Transactional
    public OperationResponse arriveTrip(UUID tripId, UUID driverUserId, Long expectedVersion, String idempotencyKey, String correlationId) {
        return executeOperation(tripId, OperationType.ARRIVE, idempotencyKey, correlationId, expectedVersion, trip -> {
            if (trip.getStatus() != TripStatus.ACCEPTED) {
                throw new TripApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "Driver can only arrive when trip is ACCEPTED");
            }

            trip.setStatus(TripStatus.ARRIVED);
            tripRepository.save(trip);

            Map<String, Object> data = Map.of(
                    "trip_id", trip.getId().toString(),
                    "driver_id", trip.getDriverId() != null ? trip.getDriverId().toString() : ""
            );
            outboxService.recordEvent(TOPIC_TRIP_EVENTS, "DriverArrived", trip.getId(), trip.getVersion().intValue(), correlationId, data);
        });
    }

    @Transactional
    public OperationResponse startTrip(UUID tripId, UUID driverUserId, Long expectedVersion, String idempotencyKey, String correlationId) {
        return executeOperation(tripId, OperationType.START, idempotencyKey, correlationId, expectedVersion, trip -> {
            if (trip.getStatus() != TripStatus.ARRIVED) {
                throw new TripApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "Trip can only start when driver has ARRIVED");
            }

            trip.setStatus(TripStatus.IN_PROGRESS);
            tripRepository.save(trip);

            Map<String, Object> data = Map.of(
                    "trip_id", trip.getId().toString(),
                    "driver_id", trip.getDriverId() != null ? trip.getDriverId().toString() : ""
            );
            outboxService.recordEvent(TOPIC_TRIP_EVENTS, "TripStarted", trip.getId(), trip.getVersion().intValue(), correlationId, data);
        });
    }

    @Transactional
    public OperationResponse completeTrip(UUID tripId, UUID driverUserId, Long expectedVersion, String idempotencyKey, String correlationId) {
        return executeOperation(tripId, OperationType.COMPLETE, idempotencyKey, correlationId, expectedVersion, trip -> {
            if (trip.getStatus() != TripStatus.IN_PROGRESS) {
                throw new TripApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "Trip can only be completed when IN_PROGRESS");
            }

            // 1. Calculate final fare from Pricing Service
            Optional<FinalFare> finalFareOpt = pricingServiceClient.calculateFinalFare(
                    trip.getId().toString(),
                    trip.getQuoteId().toString(),
                    trip.getPassengerId().toString(),
                    idempotencyKey
            );

            BigDecimal finalAmount = finalFareOpt.map(f -> new BigDecimal(f.getFare().getAmount())).orElse(trip.getFareAmount());

            // 2. Update trip status & payment details
            UUID paymentId = UUID.randomUUID();
            trip.setStatus(TripStatus.COMPLETED);
            trip.setPaymentStatus(PaymentStatus.PENDING);
            trip.setPaymentId(paymentId);
            tripRepository.save(trip);

            // 3. Outbox Event: TripCompleted
            Map<String, Object> completedData = Map.of(
                    "trip_id", trip.getId().toString(),
                    "final_fare", Map.of("amount", finalAmount.toPlainString(), "currency", trip.getFareCurrency())
            );
            outboxService.recordEvent(TOPIC_TRIP_EVENTS, "TripCompleted", trip.getId(), trip.getVersion().intValue(), correlationId, completedData);

            // 4. Outbox Command: PaymentRequested to Payment Service
            Map<String, Object> paymentData = Map.of(
                    "trip_id", trip.getId().toString(),
                    "passenger_id", trip.getPassengerId().toString(),
                    "payment_id", paymentId.toString(),
                    "attempt_number", trip.getAttemptNumber(),
                    "amount", Map.of("amount", finalAmount.toPlainString(), "currency", trip.getFareCurrency())
            );
            outboxService.recordEvent(TOPIC_PAYMENT_COMMANDS, "PaymentRequested", trip.getId(), trip.getVersion().intValue(), correlationId, paymentData);
        });
    }

    @Transactional
    public OperationResponse cancelTrip(UUID tripId, UUID callerUserId, String reason, Long expectedVersion, String idempotencyKey, String correlationId) {
        return executeOperation(tripId, OperationType.CANCEL, idempotencyKey, correlationId, expectedVersion, trip -> {
            if (trip.getStatus() == TripStatus.COMPLETED || trip.getStatus() == TripStatus.CANCELLED) {
                throw new TripApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "Trip cannot be cancelled in status: " + trip.getStatus());
            }

            // If trip was searching or offered, cancel matching
            if (trip.getStatus() == TripStatus.SEARCHING || trip.getStatus() == TripStatus.DRIVER_OFFERED) {
                Map<String, Object> matchingCancelData = Map.of(
                        "trip_id", trip.getId().toString(),
                        "attempt_id", trip.getCurrentAttemptId().toString(),
                        "reason", reason != null ? reason : "PASSENGER_CANCELLED"
                );
                outboxService.recordEvent(TOPIC_TRIP_EVENTS, "MatchingCancelled", trip.getId(), trip.getVersion().intValue(), correlationId, matchingCancelData);
            }

            trip.setStatus(TripStatus.CANCELLED);
            tripRepository.save(trip);

            Map<String, Object> cancelData = Map.of(
                    "trip_id", trip.getId().toString(),
                    "reason", reason != null ? reason : "CANCELLED"
            );
            outboxService.recordEvent(TOPIC_TRIP_EVENTS, "TripCancelled", trip.getId(), trip.getVersion().intValue(), correlationId, cancelData);
        });
    }

    @Transactional
    public OperationResponse rejectTrip(UUID tripId, UUID driverUserId, Long expectedVersion, String idempotencyKey, String correlationId) {
        return executeOperation(tripId, OperationType.REJECT, idempotencyKey, correlationId, expectedVersion, trip -> {
            // Revert back to searching with new attempt
            trip.setStatus(TripStatus.SEARCHING);
            trip.setCurrentAttemptId(UUID.randomUUID());
            trip.setDriverId(null);
            trip.setDriverUserId(null);
            tripRepository.save(trip);

            Map<String, Object> requestedData = Map.of(
                    "trip_id", trip.getId().toString(),
                    "attempt_id", trip.getCurrentAttemptId().toString(),
                    "passenger_id", trip.getPassengerId().toString()
            );
            outboxService.recordEvent(TOPIC_TRIP_EVENTS, "TripRequested", trip.getId(), trip.getVersion().intValue(), correlationId, requestedData);
        });
    }

    @Transactional
    public OperationResponse retryPayment(UUID tripId, UUID passengerId, Long expectedVersion, String idempotencyKey, String correlationId) {
        return executeOperation(tripId, OperationType.PAYMENT_RETRY, idempotencyKey, correlationId, expectedVersion, trip -> {
            if (trip.getStatus() != TripStatus.COMPLETED) {
                throw new TripApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "Payment can only be retried on COMPLETED trips");
            }
            if (trip.getPaymentStatus() == PaymentStatus.SUCCEEDED) {
                throw new TripApiException(HttpStatus.CONFLICT, "PAYMENT_ALREADY_SUCCEEDED", "Payment already succeeded");
            }

            trip.setAttemptNumber(trip.getAttemptNumber() + 1);
            trip.setPaymentStatus(PaymentStatus.PENDING);
            tripRepository.save(trip);

            Map<String, Object> paymentData = Map.of(
                    "trip_id", trip.getId().toString(),
                    "passenger_id", trip.getPassengerId().toString(),
                    "payment_id", trip.getPaymentId() != null ? trip.getPaymentId().toString() : UUID.randomUUID().toString(),
                    "attempt_number", trip.getAttemptNumber(),
                    "amount", Map.of("amount", trip.getFareAmount().toPlainString(), "currency", trip.getFareCurrency())
            );
            outboxService.recordEvent(TOPIC_PAYMENT_COMMANDS, "PaymentRequested", trip.getId(), trip.getVersion().intValue(), correlationId, paymentData);
        });
    }

    // =========================================================================
    // KAFKA CONSUMER EVENT HANDLERS
    // =========================================================================

    @Transactional
    public void handleDriverReserved(UUID tripId, UUID attemptId, UUID driverId, UUID reservationId) {
        Optional<Trip> tripOpt = tripRepository.findById(tripId);
        if (tripOpt.isEmpty()) return;
        Trip trip = tripOpt.get();

        if (trip.getStatus() != TripStatus.SEARCHING) {
            log.warn("Ignoring DriverReserved for trip {} in status {}", tripId, trip.getStatus());
            return;
        }

        trip.setDriverId(driverId);
        // Look up driver's user_id from Driver Service
        try {
            Optional<Driver> driverOpt = driverServiceClient.getDriver(driverId.toString());
            driverOpt.ifPresent(d -> trip.setDriverUserId(UUID.fromString(d.getUserId())));
        } catch (Exception e) {
            log.warn("Could not fetch driver user id for driver {}: {}", driverId, e.getMessage());
        }

        trip.setStatus(TripStatus.DRIVER_OFFERED);
        tripRepository.save(trip);

        Map<String, Object> data = Map.of(
                "trip_id", trip.getId().toString(),
                "driver_id", driverId.toString(),
                "attempt_id", attemptId.toString()
        );
        outboxService.recordEvent(TOPIC_TRIP_EVENTS, "DriverOffered", trip.getId(), trip.getVersion().intValue(), null, data);
    }

    @Transactional
    public void handleMatchingFailed(UUID tripId, UUID attemptId, String reason) {
        Optional<Trip> tripOpt = tripRepository.findById(tripId);
        if (tripOpt.isEmpty()) return;
        Trip trip = tripOpt.get();

        if (trip.getStatus() != TripStatus.SEARCHING) return;

        trip.setStatus(TripStatus.NO_DRIVER);
        tripRepository.save(trip);

        Map<String, Object> data = Map.of(
                "trip_id", trip.getId().toString(),
                "attempt_id", attemptId.toString(),
                "reason", reason != null ? reason : "NO_DRIVER"
        );
        outboxService.recordEvent(TOPIC_TRIP_EVENTS, "TripUnmatched", trip.getId(), trip.getVersion().intValue(), null, data);
    }

    @Transactional
    public void handlePaymentSucceeded(UUID tripId, UUID paymentId, Integer attemptNumber) {
        Optional<Trip> tripOpt = tripRepository.findById(tripId);
        if (tripOpt.isEmpty()) return;
        Trip trip = tripOpt.get();

        trip.setPaymentStatus(PaymentStatus.SUCCEEDED);
        tripRepository.save(trip);

        Map<String, Object> data = Map.of(
                "trip_id", trip.getId().toString(),
                "payment_status", "SUCCEEDED"
        );
        outboxService.recordEvent(TOPIC_TRIP_EVENTS, "TripPaymentUpdated", trip.getId(), trip.getVersion().intValue(), null, data);
    }

    @Transactional
    public void handlePaymentFailed(UUID tripId, UUID paymentId, Integer attemptNumber, String reason) {
        Optional<Trip> tripOpt = tripRepository.findById(tripId);
        if (tripOpt.isEmpty()) return;
        Trip trip = tripOpt.get();

        trip.setPaymentStatus(PaymentStatus.FAILED);
        tripRepository.save(trip);

        Map<String, Object> data = Map.of(
                "trip_id", trip.getId().toString(),
                "payment_status", "FAILED",
                "reason", reason != null ? reason : "DECLINED"
        );
        outboxService.recordEvent(TOPIC_TRIP_EVENTS, "TripPaymentUpdated", trip.getId(), trip.getVersion().intValue(), null, data);
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private OperationResponse executeOperation(UUID tripId, OperationType type, String idempotencyKey, String correlationId, Long expectedVersion, java.util.function.Consumer<Trip> action) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripApiException(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", "Trip not found with id: " + tripId));

        // Check concurrent pending operation
        if (tripOperationRepository.existsByTripIdAndStatus(tripId, OperationStatus.PENDING)) {
            throw new TripApiException(HttpStatus.CONFLICT, "OPERATION_IN_PROGRESS", "Another operation is in progress for this trip", true);
        }

        // Check OCC version
        if (expectedVersion != null && !trip.getVersion().equals(expectedVersion)) {
            throw new TripApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Version mismatch, please reload trip and retry", true);
        }

        UUID operationId = UUID.randomUUID();
        TripOperation op = TripOperation.builder()
                .id(operationId)
                .tripId(tripId)
                .operationType(type)
                .status(OperationStatus.PENDING)
                .idempotencyKey(idempotencyKey)
                .build();

        op = tripOperationRepository.save(op);

        try {
            action.accept(trip);
            op.setStatus(OperationStatus.SUCCEEDED);
            tripOperationRepository.save(op);
        } catch (TripApiException e) {
            op.setStatus(OperationStatus.FAILED);
            op.setErrorCode(e.getCode());
            op.setErrorMessage(e.getMessage());
            tripOperationRepository.save(op);
            throw e;
        } catch (Exception e) {
            op.setStatus(OperationStatus.FAILED);
            op.setErrorCode("INTERNAL_ERROR");
            op.setErrorMessage(e.getMessage());
            tripOperationRepository.save(op);
            throw new TripApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", e.getMessage(), true);
        }

        return toOperationResponse(op);
    }

    public TripResponse toResponse(Trip trip) {
        return TripResponse.builder()
                .id(trip.getId())
                .passenger_id(trip.getPassengerId())
                .status(trip.getStatus().name())
                .payment_status(trip.getPaymentStatus().name())
                .quote_id(trip.getQuoteId())
                .pickup(PointDto.builder()
                        .latitude(trip.getPickupLatitude())
                        .longitude(trip.getPickupLongitude())
                        .address(trip.getPickupAddress())
                        .build())
                .dropoff(PointDto.builder()
                        .latitude(trip.getDropoffLatitude())
                        .longitude(trip.getDropoffLongitude())
                        .address(trip.getDropoffAddress())
                        .build())
                .vehicle_type(trip.getVehicleType())
                .estimated_fare(MoneyDto.builder()
                        .amount(trip.getFareAmount().toPlainString())
                        .currency(trip.getFareCurrency())
                        .build())
                .driver_id(trip.getDriverId())
                .driver_user_id(trip.getDriverUserId())
                .attempt_id(trip.getCurrentAttemptId())
                .version(trip.getVersion())
                .created_at(trip.getCreatedAt() != null ? trip.getCreatedAt().toString() : null)
                .updated_at(trip.getUpdatedAt() != null ? trip.getUpdatedAt().toString() : null)
                .build();
    }

    public OperationResponse toOperationResponse(TripOperation op) {
        return OperationResponse.builder()
                .id(op.getId())
                .trip_id(op.getTripId())
                .type(op.getOperationType().name())
                .status(op.getStatus().name())
                .error_code(op.getErrorCode())
                .error_message(op.getErrorMessage())
                .created_at(op.getCreatedAt() != null ? op.getCreatedAt().toString() : null)
                .updated_at(op.getUpdatedAt() != null ? op.getUpdatedAt().toString() : null)
                .build();
    }
}
