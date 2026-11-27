package com.threecats.tripservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.threecats.tripservice.entity.OutboxEvent;
import com.threecats.tripservice.entity.enums.OutboxStatus;
import com.threecats.tripservice.event.EventEnvelope;
import com.threecats.tripservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Transactional
    public <T> void recordEvent(String topic, String eventType, UUID aggregateId, int aggregateVersion, String correlationId, T data) {
        recordEvent(topic,eventType,aggregateId,correlationId,UUID.randomUUID().toString(),data);
    }

    @Transactional
    public <T> void recordEvent(String topic,String eventType,UUID aggregateId,String correlationId,String causationId,T data) {
        long sequence=jdbc.queryForObject("INSERT INTO trip_event_sequence(trip_id,value) VALUES(?,1) ON CONFLICT(trip_id) DO UPDATE SET value=trip_event_sequence.value+1 RETURNING value",Long.class,aggregateId);
        int aggregateVersion=Math.toIntExact(sequence);
        EventEnvelope<T> envelope = EventEnvelope.create(eventType, aggregateId.toString(), aggregateVersion, correlationId, data);
        envelope.setCausationId(causationId);
        try {
            String payloadJson = objectMapper.writeValueAsString(envelope);
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .id(UUID.randomUUID())
                    .eventId(UUID.fromString(envelope.getEventId()))
                    .eventType(eventType)
                    .aggregateId(aggregateId)
                    .aggregateVersion(aggregateVersion)
                    .topic(topic)
                    .payload(payloadJson)
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .build();

            outboxEventRepository.save(outboxEvent);
            log.info("Recorded Outbox event: id={}, type={}, topic={}", outboxEvent.getEventId(), eventType, topic);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox event {}: {}", eventType, e.getMessage());
            throw new RuntimeException("Error recording outbox event", e);
        }
    }
}
