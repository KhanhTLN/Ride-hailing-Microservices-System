package com.threecats.pricingservice.controller;

import com.threecats.pricingservice.dto.QuoteInputRequest;
import com.threecats.pricingservice.dto.QuoteResponse;
import com.threecats.pricingservice.model.QuoteRecord;
import com.threecats.pricingservice.service.PricingCalculator;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
public class PricingController {

    private final PricingCalculator pricingCalculator;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public PricingController(PricingCalculator pricingCalculator,org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc=jdbc;
        this.pricingCalculator = pricingCalculator;
    }

    @PostMapping("/api/v1/quotes")
    public ResponseEntity<QuoteResponse> createQuote(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "X-User-Id", required = false) String passengerIdHeader,
            @Valid @RequestBody QuoteInputRequest request) {

        com.threecats.common.security.CurrentUser.require("PASSENGER");
        String passengerId=com.threecats.common.security.CurrentUser.id().toString();
        QuoteRecord quoteRecord = pricingCalculator.calculateQuote(request, passengerId, idempotencyKey);
        QuoteResponse response = pricingCalculator.toResponse(quoteRecord);

        return ResponseEntity.status(HttpStatus.CREATED)
                .header("X-Correlation-ID", correlationId != null ? correlationId : UUID.randomUUID().toString())
                .body(response);
    }

    @GetMapping("/internal/pricing/health/live")
    public ResponseEntity<Map<String, String>> live() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    @GetMapping("/internal/pricing/health/ready")
    public ResponseEntity<Map<String,String>> ready() {
        try { jdbc.queryForObject("SELECT 1",Integer.class); return ResponseEntity.ok(Map.of("status","UP")); }
        catch(Exception e) { return ResponseEntity.status(503).body(Map.of("code","DEPENDENCY_UNAVAILABLE")); }
    }
}
