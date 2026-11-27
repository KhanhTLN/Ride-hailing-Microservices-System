package com.threecats.tripservice.service;

import com.ds03.contracts.driver.v1.Reservation;
import com.ds03.contracts.driver.v1.ReservationStatus;
import com.ds03.contracts.pricing.v1.Quote;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.threecats.common.security.CurrentUser;
import com.threecats.tripservice.dto.*;
import com.threecats.tripservice.entity.*;
import com.threecats.tripservice.entity.enums.*;
import com.threecats.tripservice.exception.TripApiException;
import com.threecats.tripservice.grpc.*;
import com.threecats.tripservice.repository.*;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Transactions persist intent/results; all external RPCs execute between transactions. */
@Service @RequiredArgsConstructor
public class TripService {
    public static final String TOPIC_TRIP_EVENTS="ds03.trip.events.v1";
    public static final String TOPIC_PAYMENT_COMMANDS="ds03.payment.commands.v1";
    private final TripRepository trips;
    private final TripOperationRepository operations;
    private final OutboxService outbox;
    private final IdempotencyService idempotency;
    private final DriverServiceClient driver;
    private final PricingServiceClient pricing;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PlatformTransactionManager manager;
    private final jakarta.persistence.EntityManager entityManager;
    private static final List<TripStatus> TERMINAL=List.of(TripStatus.COMPLETED,TripStatus.CANCELLED,TripStatus.NO_DRIVER);

    private <T> T tx(Supplier<T> action) { return new TransactionTemplate(manager).execute(s -> action.get()); }
    private Trip locked(UUID id) { return trips.lock(id).orElseThrow(() -> error(404,"TRIP_NOT_FOUND","Trip not found")); }
    private TripApiException error(int status,String code,String message) { return new TripApiException(HttpStatus.valueOf(status),code,message); }
    private boolean participant(Trip t) { return CurrentUser.hasRole("ADMIN") || CurrentUser.id().equals(t.getPassengerId()) || CurrentUser.id().equals(t.getDriverUserId()); }
    private void readable(Trip t) { if(!participant(t)) throw error(403,"FORBIDDEN","Only trip participants can read this trip"); }
    private void noPending(Trip t) { if(operations.existsByTripIdAndStatus(t.getId(),OperationStatus.PENDING)) throw error(409,"OPERATION_IN_PROGRESS","An operation is already pending"); }

    public TripResponse createTrip(TripCreateRequest request,UUID passenger,String key,String correlation) {
        CurrentUser.require("PASSENGER");
        String scope=idempotency.scoped(passenger,"createTrip",key);
        var replay=idempotency.replay(scope,request,TripResponse.class);
        if(replay.isPresent()) return replay.get();
        Quote quote;
        try { quote=pricing.getQuote(request.getQuote_id().toString(),passenger.toString()); }
        catch(StatusRuntimeException e) {
            if(e.getStatus().getCode()==io.grpc.Status.Code.UNAVAILABLE || e.getStatus().getCode()==io.grpc.Status.Code.DEADLINE_EXCEEDED)
                throw new TripApiException(HttpStatus.SERVICE_UNAVAILABLE,"DEPENDENCY_UNAVAILABLE","Pricing is unavailable",true);
            throw error(409,"INVALID_QUOTE","Quote is invalid, expired, or owned by another passenger");
        }
        return idempotency.execute(passenger,scope,request,201,TripResponse.class,() -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,31))",Object.class,passenger.toString());
            if(trips.existsByPassengerIdAndStatusNotIn(passenger,TERMINAL)) throw error(409,"ACTIVE_TRIP_EXISTS","Passenger already has an active trip");
            if(trips.findByQuoteId(request.getQuote_id()).isPresent()) throw error(409,"QUOTE_ALREADY_USED","Quote already used");
            Trip t=Trip.builder().id(UUID.randomUUID()).passengerId(passenger).quoteId(request.getQuote_id()).status(TripStatus.SEARCHING)
                .currentAttemptId(UUID.randomUUID()).searchDeadline(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(120)).searchBudgetSeconds(120)
                .pickupLatitude(quote.getPickup().getLatitude()).pickupLongitude(quote.getPickup().getLongitude()).pickupAddress(quote.getPickup().getAddress())
                .dropoffLatitude(quote.getDropoff().getLatitude()).dropoffLongitude(quote.getDropoff().getLongitude()).dropoffAddress(quote.getDropoff().getAddress())
                .vehicleType(quote.getVehicleType().name()).fareAmount(new BigDecimal(quote.getFare().getAmount())).fareCurrency(quote.getFare().getCurrency())
                .paymentStatus(PaymentStatus.PENDING).attemptNumber(1).build();
            t=trips.saveAndFlush(t);
            // Public resource versions start at 1; refresh the managed @Version too.
            jdbc.update("UPDATE trip SET version=1 WHERE id=? AND version=0",t.getId());
            entityManager.refresh(t);
            emit(t,"TripRequested",correlation,UUID.randomUUID().toString(),requested(t));
            return toResponse(t);
        });
    }

    public TripResponse getTrip(UUID id) { Trip t=trips.findById(id).orElseThrow(() -> error(404,"TRIP_NOT_FOUND","Trip not found")); readable(t); return toResponse(t); }
    public TripListResponse listMyTrips(UUID id,String role,int limit) {
        var page=PageRequest.of(0,Math.min(Math.max(limit,1),100));
        var list=CurrentUser.hasRole("DRIVER") ? trips.findByDriverUserIdOrderByCreatedAtDesc(id,page) : trips.findByPassengerIdOrderByCreatedAtDesc(id,page);
        return TripListResponse.builder().items(list.stream().map(this::toResponse).toList()).build();
    }
    public OperationResponse getOperation(UUID id) {
        TripOperation op=operations.findById(id).orElseThrow(() -> error(404,"OPERATION_NOT_FOUND","Operation not found"));
        Trip t=trips.findById(op.getTripId()).orElseThrow();
        if(!participant(t) && !CurrentUser.id().equals(op.getCallerId())) throw error(403,"FORBIDDEN","Operation is owned by another caller");
        return toOperationResponse(op);
    }

    public OperationResponse submit(UUID id,OperationType type,UUID caller,Long version,String key,String correlation,UUID attempt,UUID reservation,String reason) {
        if(version==null || version<1) throw error(422,"INVALID_ARGUMENT","expected_version must be positive");
        String scope=idempotency.scoped(caller,type.name(),key);
        Map<String,Object> request=new LinkedHashMap<>(); request.put("trip_id",id); request.put("expected_version",version);
        request.put("attempt_id",attempt); request.put("reservation_id",reservation); request.put("reason",reason);
        return idempotency.execute(caller,scope,request,202,OperationResponse.class,() -> {
            Trip t=locked(id);
            boolean ownDriver=CurrentUser.hasRole("DRIVER") && caller.equals(t.getDriverUserId());
            boolean ownPassenger=CurrentUser.hasRole("PASSENGER") && caller.equals(t.getPassengerId());
            if(type==OperationType.CANCEL) {
                if(!ownPassenger && !(ownDriver && List.of(TripStatus.ACCEPTED,TripStatus.ARRIVED).contains(t.getStatus()))) throw error(403,"FORBIDDEN","Caller cannot cancel this trip");
            } else if(type==OperationType.PAYMENT_RETRY) {
                if(!ownPassenger) throw error(403,"FORBIDDEN","Only the passenger can retry payment");
            } else if(!ownDriver) throw error(403,"FORBIDDEN","Only the assigned driver can perform this action");
            noPending(t);
            if(!version.equals(t.getVersion())) throw error(409,"VERSION_CONFLICT","Reload trip before retrying");
            boolean valid=switch(type) {
                case ACCEPT,REJECT -> t.getStatus()==TripStatus.DRIVER_OFFERED && t.getReservationId()!=null;
                case ARRIVE -> t.getStatus()==TripStatus.ACCEPTED;
                case START -> t.getStatus()==TripStatus.ARRIVED;
                case COMPLETE -> t.getStatus()==TripStatus.IN_PROGRESS;
                case CANCEL -> List.of(TripStatus.SEARCHING,TripStatus.DRIVER_OFFERED,TripStatus.ACCEPTED,TripStatus.ARRIVED).contains(t.getStatus());
                case PAYMENT_RETRY -> t.getStatus()==TripStatus.COMPLETED && t.getPaymentStatus()==PaymentStatus.FAILED;
                default -> false;
            };
            if(!valid) throw error(409,"INVALID_TRANSITION","Action is not allowed in the current state");
            if((type==OperationType.ACCEPT || type==OperationType.REJECT) && (!Objects.equals(attempt,t.getCurrentAttemptId()) || !Objects.equals(reservation,t.getReservationId())))
                throw error(409,"STALE_ATTEMPT","Offer attempt or reservation is no longer current");
            if(type==OperationType.ACCEPT && !t.getReservationExpiresAt().isAfter(OffsetDateTime.now())) throw error(409,"RESERVATION_EXPIRED","Driver offer expired");
            TripOperation op=newOperation(t,type,caller,correlation);
            op.setIdempotencyKey(key); op.setExpectedVersion(version);
            op.setReason(reason);
            return toOperationResponse(operations.saveAndFlush(op));
        });
    }
    private TripOperation newOperation(Trip t,OperationType type,UUID caller,String correlation) {
        return TripOperation.builder().id(UUID.randomUUID()).tripId(t.getId()).operationType(type).callerId(caller)
            .reservationId(t.getReservationId()).driverId(t.getDriverId()).attemptId(t.getCurrentAttemptId())
            .correlationId(UUID.fromString(correlation)).status(OperationStatus.PENDING).step("START").retries(0).nextRetryAt(OffsetDateTime.now()).build();
    }

    /** Called by the worker under a session advisory lock for this operation. */
    public void advance(UUID id) {
        TripOperation snapshot=operations.findById(id).orElseThrow();
        if(snapshot.getStatus()!=OperationStatus.PENDING) return;
        Trip t=trips.findById(snapshot.getTripId()).orElseThrow();
        String key=mutation(id,snapshot.getStep());
        try {
            switch(snapshot.getOperationType()) {
                case ACCEPT -> {
                    Reservation reserved=driver.confirmReservation(snapshot.getReservationId().toString(),t.getId().toString(),key);
                    requireReservation(reserved,t.getId(),snapshot.getDriverId());
                    if(reserved.getStatus()!=ReservationStatus.CONFIRMED) throw error(409,"RESERVATION_EXPIRED","Reservation was not confirmed");
                    tx(() -> { Trip current=locked(t.getId()); TripOperation op=operations.findById(id).orElseThrow();
                        if(!pending(op,"START")) return null;
                        current.setStatus(TripStatus.ACCEPTED); trips.saveAndFlush(current);
                        emit(current,"TripAccepted",corr(op),id.toString(),state(current)); succeed(op); return null; });
                }
                case CANCEL -> {
                    if(snapshot.getStep().equals("START")) tx(() -> {
                        Trip current=locked(t.getId()); TripOperation op=operations.findById(id).orElseThrow();
                        if(!pending(op,"START")) return null;
                        op.setReservationId(current.getReservationId());
                        current.setStatus(TripStatus.CANCELLED); trips.saveAndFlush(current);
                        String reason=Objects.toString(op.getReason(),"USER_CANCELLED");
                        emitCancel(current,corr(op),id.toString(),reason);
                        Map<String,Object> data=unassignedState(current); data.put("reason",reason);
                        emit(current,"TripCancelled",corr(op),id.toString(),data);
                        op.setStep("RELEASE"); operations.save(op); return null;
                    });
                    releaseAndFinish(id);
                }
                case REJECT -> {
                    if(snapshot.getStep().equals("START")) tx(() -> {
                        Trip current=locked(t.getId()); TripOperation op=operations.findById(id).orElseThrow();
                        if(!pending(op,"START")) return null;
                        emitCancel(current,corr(op),id.toString(),"DRIVER_REJECTED_OR_EXPIRED");
                        op.setStep("RELEASE"); operations.save(op); return null;
                    });
                    TripOperation op=operations.findById(id).orElseThrow();
                    if(!pending(op,"RELEASE")) return;
                    release(op);
                    tx(() -> { Trip current=locked(t.getId()); TripOperation pending=operations.findById(id).orElseThrow();
                        if(!pending(pending,"RELEASE")) return null;
                        if(pending.getDriverId()!=null) { var excluded=excluded(current); if(!excluded.contains(pending.getDriverId().toString())) excluded.add(pending.getDriverId().toString()); current.setExcludedDriverIds(String.join(",",excluded)); }
                        clearOffer(current); current.setCurrentAttemptId(UUID.randomUUID());
                        current.setStatus(current.getSearchDeadline().isAfter(OffsetDateTime.now()) ? TripStatus.SEARCHING : TripStatus.NO_DRIVER);
                        trips.saveAndFlush(current);
                        if(current.getStatus()==TripStatus.SEARCHING) emit(current,"TripRequested",corr(pending),id.toString(),requested(current));
                        else unmatched(current,corr(pending),id.toString(),"DEADLINE_EXCEEDED");
                        succeed(pending); return null; });
                }
                case COMPLETE -> {
                    if(snapshot.getStep().equals("START")) {
                        var fare=pricing.calculateFinalFare(t.getId().toString(),t.getQuoteId().toString(),t.getPassengerId().toString(),key);
                        BigDecimal amount=new BigDecimal(fare.getFare().getAmount());
                        tx(() -> { Trip current=locked(t.getId()); TripOperation op=operations.findById(id).orElseThrow();
                            if(!pending(op,"START")) return null;
                            current.setStatus(TripStatus.COMPLETED); current.setFareAmount(amount); current.setFareCurrency(fare.getFare().getCurrency());
                            current.setPaymentId(UUID.randomUUID()); current.setAttemptNumber(1); current.setPaymentStatus(PaymentStatus.PENDING);
                            trips.saveAndFlush(current);
                            Map<String,Object> data=state(current); data.put("final_fare",money(current));
                            emit(current,"TripCompleted",corr(op),id.toString(),data); paymentRequested(current,corr(op),id.toString());
                            op.setStep("RELEASE"); operations.save(op); return null; });
                    }
                    releaseAndFinish(id);
                }
                case ARRIVE,START,PAYMENT_RETRY -> tx(() -> {
                    Trip current=locked(t.getId()); TripOperation op=operations.findById(id).orElseThrow();
                    if(!pending(op,"START")) return null;
                    if(op.getOperationType()==OperationType.PAYMENT_RETRY) { current.setAttemptNumber(current.getAttemptNumber()+1); current.setPaymentStatus(PaymentStatus.PENDING); }
                    else current.setStatus(op.getOperationType()==OperationType.ARRIVE ? TripStatus.ARRIVED : TripStatus.IN_PROGRESS);
                    trips.saveAndFlush(current);
                    if(op.getOperationType()==OperationType.PAYMENT_RETRY) paymentRequested(current,corr(op),id.toString());
                    else emit(current,op.getOperationType()==OperationType.ARRIVE ? "DriverArrived" : "TripStarted",corr(op),id.toString(),state(current));
                    succeed(op); return null;
                });
                default -> throw new IllegalStateException("Unsupported operation");
            }
        } catch(Exception e) {
            boolean definitive=e instanceof TripApiException || (e instanceof StatusRuntimeException rpc &&
                    List.of(io.grpc.Status.Code.FAILED_PRECONDITION,io.grpc.Status.Code.INVALID_ARGUMENT).contains(rpc.getStatus().getCode()) && snapshot.getOperationType()==OperationType.ACCEPT);
            tx(() -> { locked(snapshot.getTripId()); TripOperation op=operations.findById(id).orElseThrow();
                if(op.getStatus()!=OperationStatus.PENDING) return null;
                op.setRetries(op.getRetries()+1); op.setErrorCode(e instanceof TripApiException api ? api.getCode() : "DEPENDENCY_UNAVAILABLE");
                op.setErrorMessage("Operation requires reconciliation: "+e.getClass().getSimpleName());
                op.setNextRetryAt(OffsetDateTime.now().plusSeconds(Math.min(30,1L<<Math.min(op.getRetries(),5))));
                if(definitive) op.setStatus(OperationStatus.FAILED);
                operations.save(op); return null; });
        }
    }
    private void releaseAndFinish(UUID id) {
        TripOperation op=operations.findById(id).orElseThrow();
        if(!pending(op,"RELEASE")) return;
        release(op);
        tx(() -> { locked(op.getTripId()); succeed(operations.findById(id).orElseThrow()); return null; });
    }
    private void release(TripOperation op) {
        if(op.getReservationId()==null) return;
        Reservation result=driver.releaseReservation(op.getReservationId().toString(),op.getTripId().toString(),op.getOperationType().name(),mutation(op.getId(),"RELEASE"));
        if(!result.getTripId().equals(op.getTripId().toString()) || !List.of(ReservationStatus.RELEASED,ReservationStatus.EXPIRED).contains(result.getStatus())) throw new IllegalStateException("Reservation cleanup is unresolved");
    }
    private boolean pending(TripOperation op,String step) { return op.getStatus()==OperationStatus.PENDING && op.getStep().equals(step); }
    private void succeed(TripOperation op) { if(op.getStatus()!=OperationStatus.PENDING) return; op.setStatus(OperationStatus.SUCCEEDED); op.setStep("DONE"); op.setErrorCode(null); op.setErrorMessage(null); operations.save(op); }
    public static String mutation(UUID id,String step) { return UUID.nameUUIDFromBytes((id+":"+step).getBytes(StandardCharsets.UTF_8)).toString(); }
    private String corr(TripOperation op) { return op.getCorrelationId().toString(); }

    /** Persist event dedup and state atomically. Remote Driver resolution is deferred. */
    public void consume(JsonNode event) {
        UUID eventId=uuid(event,"event_id"), tripId=uuid(event,"aggregate_id");
        JsonNode data=event.path("data");
        if(!tripId.equals(uuid(data,"trip_id"))) throw new IllegalArgumentException("Trip key mismatch");
        String hash=idempotency.hash(mapper.convertValue(event,Map.class));
        tx(() -> {
            int inserted=jdbc.update("INSERT INTO trip_processed_event(event_id,request_hash) VALUES(?,?) ON CONFLICT DO NOTHING",eventId,hash);
            if(inserted==0) {
                if(!hash.equals(jdbc.queryForObject("SELECT request_hash FROM trip_processed_event WHERE event_id=?",String.class,eventId))) throw new IllegalArgumentException("Event ID reused with different content");
                return null;
            }
            var found=trips.lock(tripId); if(found.isEmpty()) return null;
            Trip t=found.get(); String type=event.path("event_type").asText();
            String correlation=uuid(event,"correlation_id").toString();
            if(type.equals("DriverReserved")) {
                UUID attempt=uuid(data,"attempt_id"), reserved=uuid(data,"reservation_id"), driverId=uuid(data,"driver_id");
                if(t.getStatus()!=TripStatus.SEARCHING || !attempt.equals(t.getCurrentAttemptId())) {
                    if(!reserved.equals(t.getReservationId()) || TERMINAL.contains(t.getStatus())) cleanup(tripId,reserved);
                    return null;
                }
                if(t.getReservationId()!=null && !reserved.equals(t.getReservationId())) { cleanup(tripId,reserved); return null; }
                t.setReservationId(reserved); t.setDriverId(driverId); t.setReservationExpiresAt(OffsetDateTime.parse(data.path("expires_at").asText()));
                t.setMatchingCorrelationId(UUID.fromString(correlation)); t.setMatchingEventId(eventId);
                trips.save(t);
            } else if(type.equals("MatchingFailed")) {
                if(t.getStatus()!=TripStatus.SEARCHING || !uuid(data,"attempt_id").equals(t.getCurrentAttemptId()) || t.getReservationId()!=null) return null;
                t.setStatus(TripStatus.NO_DRIVER); trips.saveAndFlush(t); unmatched(t,correlation,eventId.toString(),data.path("reason").asText());
            } else if(type.equals("PaymentSucceeded") || type.equals("PaymentFailed")) {
                if(t.getStatus()!=TripStatus.COMPLETED || t.getPaymentId()==null || !t.getPaymentId().equals(uuid(data,"payment_id"))
                    || !t.getPassengerId().equals(uuid(data,"passenger_id")) || t.getAttemptNumber()!=data.path("attempt_number").asInt()
                    || t.getPaymentStatus()!=PaymentStatus.PENDING || !t.getFareCurrency().equals(data.path("amount").path("currency").asText())
                    || t.getFareAmount().compareTo(new BigDecimal(data.path("amount").path("amount").asText()))!=0) return null;
                t.setPaymentStatus(type.equals("PaymentSucceeded") ? PaymentStatus.SUCCEEDED : PaymentStatus.FAILED); trips.saveAndFlush(t);
                Map<String,Object> payload=new LinkedHashMap<>(); payload.put("trip_id",t.getId().toString()); payload.put("passenger_id",t.getPassengerId().toString());
                payload.put("payment_id",t.getPaymentId().toString()); payload.put("payment_status",t.getPaymentStatus().name()); payload.put("trip_version",t.getVersion());
                emit(t,"TripPaymentUpdated",correlation,eventId.toString(),payload);
            }
            return null;
        });
    }
    public static UUID uuid(JsonNode node,String field) { String value=node.path(field).asText(); UUID id=UUID.fromString(value); if(!id.toString().equals(value)) throw new IllegalArgumentException("Noncanonical UUID"); return id; }
    private void cleanup(UUID trip,UUID reservation) { jdbc.update("INSERT INTO trip_reservation_cleanup(reservation_id,trip_id) VALUES(?,?) ON CONFLICT DO NOTHING",reservation,trip); }

    public void resolveOffer(UUID tripId) {
        Trip snapshot=trips.findById(tripId).orElseThrow();
        if(snapshot.getStatus()!=TripStatus.SEARCHING || snapshot.getReservationId()==null) return;
        // Validate the authoritative reservation before trusting Kafka data.
        Reservation reservation=driver.getReservation(snapshot.getReservationId().toString());
        requireReservation(reservation,snapshot.getId(),snapshot.getDriverId());
        if(reservation.getStatus()!=ReservationStatus.HELD) return; // Expiry worker restarts the attempt.
        var profile=driver.getDriver(snapshot.getDriverId().toString());
        tx(() -> { Trip t=locked(tripId);
            if(t.getStatus()!=TripStatus.SEARCHING || !Objects.equals(t.getReservationId(),snapshot.getReservationId())) return null;
            t.setDriverUserId(UUID.fromString(profile.getUserId())); t.setStatus(TripStatus.DRIVER_OFFERED);
            t.setReservationExpiresAt(OffsetDateTime.ofInstant(Instant.ofEpochSecond(reservation.getExpiresAt().getSeconds(),reservation.getExpiresAt().getNanos()),ZoneOffset.UTC));
            trips.saveAndFlush(t);
            Map<String,Object> data=state(t); data.put("attempt_id",t.getCurrentAttemptId().toString()); data.put("reservation_id",t.getReservationId().toString());
            data.put("expires_at",t.getReservationExpiresAt().toString()); data.put("pickup",point(t,true)); data.put("dropoff",point(t,false)); data.put("estimated_fare",money(t));
            emit(t,"DriverOffered",t.getMatchingCorrelationId().toString(),t.getMatchingEventId().toString(),data); return null;
        });
    }
    private void requireReservation(Reservation reservation,UUID trip,UUID driverId) {
        if(!reservation.getTripId().equals(trip.toString()) || !reservation.getDriverId().equals(driverId.toString())) throw error(409,"RESERVATION_MISMATCH","Driver reservation does not match this trip");
    }
    public void expire(UUID tripId) {
        tx(() -> { Trip t=locked(tripId);
            if(operations.existsByTripIdAndStatus(t.getId(),OperationStatus.PENDING)) return null;
            if(!List.of(TripStatus.SEARCHING,TripStatus.DRIVER_OFFERED).contains(t.getStatus())) return null;
            if(t.getReservationId()!=null && t.getReservationExpiresAt()!=null && !t.getReservationExpiresAt().isAfter(OffsetDateTime.now()))
                operations.save(newOperation(t,OperationType.REJECT,t.getDriverUserId()!=null ? t.getDriverUserId() : t.getPassengerId(),UUID.randomUUID().toString()));
            else if(!t.getSearchDeadline().isAfter(OffsetDateTime.now())) {
                if(t.getReservationId()!=null) cleanup(t.getId(),t.getReservationId());
                t.setStatus(TripStatus.NO_DRIVER); trips.saveAndFlush(t);
                String correlation=UUID.randomUUID().toString(), cause=UUID.randomUUID().toString(); emitCancel(t,correlation,cause,"DEADLINE_EXCEEDED"); unmatched(t,correlation,cause,"DEADLINE_EXCEEDED");
            }
            return null;
        });
    }
    public void cleanupReservation(UUID reservationId,UUID tripId) {
        Reservation result=driver.releaseReservation(reservationId.toString(),tripId.toString(),"STALE_MATCHING_RESULT",mutation(reservationId,"LATE_RELEASE"));
        if(!List.of(ReservationStatus.RELEASED,ReservationStatus.EXPIRED).contains(result.getStatus())) throw new IllegalStateException("Cleanup not terminal");
        jdbc.update("UPDATE trip_reservation_cleanup SET done=true,last_error=null WHERE reservation_id=?",reservationId);
    }

    private List<String> excluded(Trip t) { return new ArrayList<>(t.getExcludedDriverIds()==null || t.getExcludedDriverIds().isBlank() ? List.of() : Arrays.asList(t.getExcludedDriverIds().split(","))); }
    private void clearOffer(Trip t) { t.setDriverId(null); t.setDriverUserId(null); t.setReservationId(null); t.setReservationExpiresAt(null); }
    private Map<String,Object> requested(Trip t) {
        return Map.of("trip_id",t.getId().toString(),"attempt_id",t.getCurrentAttemptId().toString(),"passenger_id",t.getPassengerId().toString(),
            "pickup",point(t,true),"vehicle_type",t.getVehicleType(),"search_deadline",t.getSearchDeadline().toString(),"excluded_driver_ids",excluded(t));
    }
    private Map<String,Object> point(Trip t,boolean pickup) { return Map.of("latitude",pickup ? t.getPickupLatitude() : t.getDropoffLatitude(),"longitude",pickup ? t.getPickupLongitude() : t.getDropoffLongitude(),"address",Objects.toString(pickup ? t.getPickupAddress() : t.getDropoffAddress(),"")); }
    private Map<String,Object> money(Trip t) { return Map.of("amount",t.getFareAmount().toBigIntegerExact().toString(),"currency",t.getFareCurrency()); }
    private Map<String,Object> unassignedState(Trip t) {
        Map<String,Object> map=new LinkedHashMap<>(); map.put("trip_id",t.getId().toString()); map.put("passenger_id",t.getPassengerId().toString()); map.put("status",t.getStatus().name()); map.put("trip_version",t.getVersion()); return map;
    }
    private Map<String,Object> state(Trip t) { Map<String,Object> map=unassignedState(t); map.put("driver_id",t.getDriverId().toString()); map.put("driver_user_id",t.getDriverUserId().toString()); return map; }
    private void emit(Trip t,String type,String correlation,String cause,Object data) { outbox.recordEvent(TOPIC_TRIP_EVENTS,type,t.getId(),correlation,cause,data); }
    private void emitCancel(Trip t,String correlation,String cause,String reason) { emit(t,"MatchingCancelled",correlation,cause,Map.of("trip_id",t.getId().toString(),"attempt_id",t.getCurrentAttemptId().toString(),"reason",reason)); }
    private void unmatched(Trip t,String correlation,String cause,String reason) { Map<String,Object> data=unassignedState(t); data.put("reason",reason); emit(t,"TripUnmatched",correlation,cause,data); }
    private void paymentRequested(Trip t,String correlation,String cause) {
        outbox.recordEvent(TOPIC_PAYMENT_COMMANDS,"PaymentRequested",t.getId(),correlation,cause,Map.of("trip_id",t.getId().toString(),"passenger_id",t.getPassengerId().toString(),"payment_id",t.getPaymentId().toString(),"attempt_number",t.getAttemptNumber(),"amount",money(t),"method","SIMULATED"));
    }
    public TripResponse toResponse(Trip t) {
        return TripResponse.builder().id(t.getId()).passenger_id(t.getPassengerId()).status(t.getStatus().name()).payment_status(t.getPaymentStatus().name()).quote_id(t.getQuoteId())
            .pickup(PointDto.builder().latitude(t.getPickupLatitude()).longitude(t.getPickupLongitude()).address(t.getPickupAddress()).build())
            .dropoff(PointDto.builder().latitude(t.getDropoffLatitude()).longitude(t.getDropoffLongitude()).address(t.getDropoffAddress()).build())
            .vehicle_type(t.getVehicleType()).estimated_fare(MoneyDto.builder().amount(t.getFareAmount().toBigIntegerExact().toString()).currency(t.getFareCurrency()).build())
            .driver_id(t.getDriverId()).driver_user_id(t.getDriverUserId()).reservation_id(t.getReservationId()).attempt_id(t.getCurrentAttemptId()).version(t.getVersion())
            .final_fare(t.getStatus()==TripStatus.COMPLETED ? MoneyDto.builder().amount(t.getFareAmount().toBigIntegerExact().toString()).currency(t.getFareCurrency()).build() : null)
            .created_at(t.getCreatedAt()!=null ? t.getCreatedAt().toString() : null).updated_at(t.getUpdatedAt()!=null ? t.getUpdatedAt().toString() : null).build();
    }
    public OperationResponse toOperationResponse(TripOperation op) { return OperationResponse.builder().id(op.getId()).trip_id(op.getTripId()).type(op.getOperationType().name()).status(op.getStatus().name()).error_code(op.getErrorCode()).error_message(op.getErrorMessage()).created_at(op.getCreatedAt()!=null ? op.getCreatedAt().toString() : null).updated_at(op.getUpdatedAt()!=null ? op.getUpdatedAt().toString() : null).build(); }
}
