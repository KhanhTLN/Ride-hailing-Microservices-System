package com.threecats.tripservice.worker;

import com.threecats.tripservice.entity.OutboxEvent;
import com.threecats.tripservice.entity.enums.OutboxStatus;
import com.threecats.tripservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisherWorker {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findByStatusOrderByCreatedAtAsc(
                OutboxStatus.PENDING,
                PageRequest.of(0, 50)
        );

        if (pendingEvents.isEmpty()) {
            return;
        }

        for (OutboxEvent event : pendingEvents) {
            try {
                String key = event.getAggregateId().toString();
                kafkaTemplate.send(event.getTopic(), key, event.getPayload()).get(5, TimeUnit.SECONDS);

                event.setStatus(OutboxStatus.SENT);
                event.setSentAt(OffsetDateTime.now());
                outboxEventRepository.save(event);
                log.debug("Published outbox event {} to topic {}", event.getEventId(), event.getTopic());
            } catch (Exception e) {
                log.error("Failed to publish outbox event {}: {}", event.getEventId(), e.getMessage());
                event.setRetryCount(event.getRetryCount() + 1);
                event.setLastError(e.getMessage());
                if (event.getRetryCount() >= 5) {
                    event.setStatus(OutboxStatus.FAILED);
                }
                outboxEventRepository.save(event);
            }
        }
    }
}
