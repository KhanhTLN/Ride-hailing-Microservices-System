package com.threecats.tripservice.controller;

import com.threecats.common.security.CurrentUser;
import com.threecats.common.context.CorrelationContext;
import com.threecats.tripservice.dto.*;
import com.threecats.tripservice.entity.enums.OperationType;
import com.threecats.tripservice.service.TripService;
import com.threecats.tripservice.exception.TripApiException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;

@RestController @RequiredArgsConstructor
public class TripController {
    private final TripService service;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    @PostMapping("/api/v1/trips")
    public ResponseEntity<TripResponse> create(@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody TripCreateRequest request) {
        return ResponseEntity.status(201).body(service.createTrip(request,CurrentUser.id(),key,CorrelationContext.get()));
    }
    @GetMapping("/api/v1/trips/{trip_id}")
    public TripResponse get(@PathVariable("trip_id") UUID id) { return service.getTrip(id); }
    @GetMapping("/api/v1/trips/me")
    public TripListResponse list(@RequestParam(defaultValue="20") int limit) { return service.listMyTrips(CurrentUser.id(),CurrentUser.hasRole("DRIVER") ? "DRIVER" : "PASSENGER",limit); }
    @PostMapping("/api/v1/trips/{trip_id}/{action}")
    public ResponseEntity<OperationResponse> action(@PathVariable("trip_id") UUID id,@PathVariable String action,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody TripActionRequest request) {
        OperationType type=switch(action) {
            case "accept" -> OperationType.ACCEPT; case "reject" -> OperationType.REJECT; case "arrive" -> OperationType.ARRIVE;
            case "start" -> OperationType.START; case "complete" -> OperationType.COMPLETE; case "cancel" -> OperationType.CANCEL;
            case "retry-payment" -> OperationType.PAYMENT_RETRY;
            default -> throw new TripApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Unknown trip action");
        };
        boolean offer=type==OperationType.ACCEPT || type==OperationType.REJECT;
        if(offer && (request.getAttempt_id()==null || request.getReservation_id()==null)) throw new TripApiException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_ARGUMENT","attempt_id and reservation_id are required");
        if(type==OperationType.CANCEL && (request.getReason()==null || request.getReason().isBlank())) throw new TripApiException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_ARGUMENT","Cancel reason is required");
        if(!offer && (request.getAttempt_id()!=null || request.getReservation_id()!=null)) throw new TripApiException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_ARGUMENT","Offer IDs are only valid for accept/reject");
        if(type!=OperationType.CANCEL && request.getReason()!=null) throw new TripApiException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_ARGUMENT","Reason is only valid for cancel");
        OperationResponse response=service.submit(id,type,CurrentUser.id(),request.getExpected_version(),key,CorrelationContext.get(),request.getAttempt_id(),request.getReservation_id(),request.getReason());
        return ResponseEntity.accepted().location(URI.create("/api/v1/operations/"+response.getId())).body(response);
    }
    @GetMapping("/api/v1/operations/{operation_id}")
    public OperationResponse operation(@PathVariable("operation_id") UUID id) { return service.getOperation(id); }
    @GetMapping("/internal/trip/health/live")
    public Map<String,String> health() { return Map.of("status","UP"); }
    @GetMapping("/internal/trip/health/ready")
    public ResponseEntity<Map<String,String>> ready() {
        try { jdbc.queryForObject("SELECT 1",Integer.class); return ResponseEntity.ok(Map.of("status","UP")); }
        catch(Exception e) { return ResponseEntity.status(503).body(Map.of("code","DEPENDENCY_UNAVAILABLE")); }
    }
}
