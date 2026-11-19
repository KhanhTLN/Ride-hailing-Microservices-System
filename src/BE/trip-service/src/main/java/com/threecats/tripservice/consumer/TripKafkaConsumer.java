package com.threecats.tripservice.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.threecats.tripservice.service.TripService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class TripKafkaConsumer {

    private final TripService tripService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "ds03.matching.events.v1", groupId = "trip-matching-v1")
    public void consumeMatchingEvents(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String eventType = root.path("event_type").asText();
            JsonNode data = root.path("data");

            log.info("Received matching event: {}", eventType);

            if ("DriverReserved".equalsIgnoreCase(eventType)) {
                UUID tripId = UUID.fromString(data.path("trip_id").asText());
                UUID attemptId = UUID.fromString(data.path("attempt_id").asText());
                UUID driverId = UUID.fromString(data.path("driver_id").asText());
                UUID reservationId = data.has("reservation_id") && !data.path("reservation_id").asText().isBlank()
                        ? UUID.fromString(data.path("reservation_id").asText())
                        : null;

                tripService.handleDriverReserved(tripId, attemptId, driverId, reservationId);
            } else if ("MatchingFailed".equalsIgnoreCase(eventType)) {
                UUID tripId = UUID.fromString(data.path("trip_id").asText());
                UUID attemptId = UUID.fromString(data.path("attempt_id").asText());
                String reason = data.path("reason").asText();

                tripService.handleMatchingFailed(tripId, attemptId, reason);
            }
        } catch (Exception e) {
            log.error("Error processing matching event: {}", e.getMessage(), e);
            throw new RuntimeException("Error processing matching event", e);
        }
    }

    @KafkaListener(topics = "ds03.payment.events.v1", groupId = "trip-payment-v1")
    public void consumePaymentEvents(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String eventType = root.path("event_type").asText();
            JsonNode data = root.path("data");

            log.info("Received payment event: {}", eventType);

            if ("PaymentSucceeded".equalsIgnoreCase(eventType)) {
                UUID tripId = UUID.fromString(data.path("trip_id").asText());
                UUID paymentId = UUID.fromString(data.path("payment_id").asText());
                int attemptNumber = data.path("attempt_number").asInt(1);

                tripService.handlePaymentSucceeded(tripId, paymentId, attemptNumber);
            } else if ("PaymentFailed".equalsIgnoreCase(eventType)) {
                UUID tripId = UUID.fromString(data.path("trip_id").asText());
                UUID paymentId = UUID.fromString(data.path("payment_id").asText());
                int attemptNumber = data.path("attempt_number").asInt(1);
                String reason = data.path("reason").asText();

                tripService.handlePaymentFailed(tripId, paymentId, attemptNumber, reason);
            }
        } catch (Exception e) {
            log.error("Error processing payment event: {}", e.getMessage(), e);
            throw new RuntimeException("Error processing payment event", e);
        }
    }
}
