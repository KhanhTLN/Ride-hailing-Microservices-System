package com.threecats.tripservice.worker;

import com.threecats.tripservice.service.TripService;
import com.threecats.common.context.CorrelationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component @RequiredArgsConstructor @Slf4j
public class SagaWorker {
    private final JdbcTemplate jdbc;
    private final TripService service;
    private final WorkerLock locks;

    @Scheduled(fixedDelay=200)
    public void tick() {
        try {
            for(var row:jdbc.queryForList("SELECT id,correlation_id FROM trip_operation WHERE status='PENDING' AND (next_retry_at IS NULL OR next_retry_at<=now()) ORDER BY created_at LIMIT 5")) {
                UUID id=(UUID)row.get("id");
                locks.run("operation:"+id,() -> {
                    CorrelationContext.set(String.valueOf(row.get("correlation_id")));
                    try { service.advance(id); } finally { CorrelationContext.clear(); }
                });
            }
            for(var row:jdbc.queryForList("SELECT reservation_id,trip_id FROM trip_reservation_cleanup WHERE NOT done AND next_retry_at<=now() LIMIT 5")) {
                UUID id=(UUID)row.get("reservation_id"), trip=(UUID)row.get("trip_id");
                locks.run("cleanup:"+id,() -> {
                    try { service.cleanupReservation(id,trip); }
                    catch(Exception e) { jdbc.update("UPDATE trip_reservation_cleanup SET retries=retries+1,next_retry_at=now()+interval '5 seconds',last_error=? WHERE reservation_id=?",e.getClass().getSimpleName(),id); }
                });
            }
            for(UUID id:jdbc.queryForList("SELECT id FROM trip WHERE status IN ('SEARCHING','DRIVER_OFFERED') AND (search_deadline<=now() OR reservation_expires_at<=now()) LIMIT 5",UUID.class))
                locks.run("trip:"+id,() -> service.expire(id));
            for(UUID id:jdbc.queryForList("SELECT id FROM trip WHERE status='SEARCHING' AND reservation_id IS NOT NULL AND reservation_expires_at>now() LIMIT 5",UUID.class))
                locks.run("trip:"+id,() -> { try { service.resolveOffer(id); } catch(Exception e) { log.debug("Offer resolution will retry for trip {}: {}",id,e.getClass().getSimpleName()); } });
        } catch(Exception e) { log.error("Saga tick failed: {}",e.getClass().getSimpleName()); }
    }
}
