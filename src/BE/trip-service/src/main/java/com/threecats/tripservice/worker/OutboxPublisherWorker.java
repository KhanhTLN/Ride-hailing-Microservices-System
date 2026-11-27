package com.threecats.tripservice.worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component @RequiredArgsConstructor @Slf4j
public class OutboxPublisherWorker {
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String,String> kafka;
    private final WorkerLock locks;
    @Scheduled(fixedDelay=200)
    public void publishPendingEvents() {
        try { locks.run("trip-outbox",() -> {
            var rows=jdbc.queryForList("SELECT id,aggregate_id,topic,payload::text AS payload FROM outbox_event WHERE status='PENDING' ORDER BY created_at,aggregate_version LIMIT 20");
            for(var row:rows) {
                try {
                    kafka.send((String)row.get("topic"),row.get("aggregate_id").toString(),(String)row.get("payload")).get(5,TimeUnit.SECONDS);
                    jdbc.update("UPDATE outbox_event SET status='SENT',sent_at=now(),last_error=null WHERE id=?",row.get("id"));
                } catch(Exception e) {
                    // Keep a durable PENDING row and preserve producer ordering.
                    jdbc.update("UPDATE outbox_event SET retry_count=retry_count+1,last_error=? WHERE id=?",e.getClass().getSimpleName(),row.get("id"));
                    break;
                }
            }
        }); } catch(Exception e) { log.error("Outbox tick failed: {}",e.getClass().getSimpleName()); }
    }
}
