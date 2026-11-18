package com.threecats.tripservice.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventEnvelope<T> {

    @JsonProperty("event_id")
    private String eventId;

    @JsonProperty("event_type")
    private String eventType;

    @JsonProperty("schema_version")
    @Builder.Default
    private Integer schemaVersion = 1;

    @JsonProperty("producer")
    @Builder.Default
    private String producer = "trip";

    @JsonProperty("occurred_at")
    private String occurredAt;

    @JsonProperty("correlation_id")
    private String correlationId;

    @JsonProperty("causation_id")
    private String causationId;

    @JsonProperty("aggregate_id")
    private String aggregateId;

    @JsonProperty("aggregate_version")
    @Builder.Default
    private Integer aggregateVersion = 1;

    @JsonProperty("data")
    private T data;

    public static <T> EventEnvelope<T> create(String eventType, String aggregateId, int aggregateVersion, String correlationId, T data) {
        String eventId = UUID.randomUUID().toString();
        return EventEnvelope.<T>builder()
                .eventId(eventId)
                .eventType(eventType)
                .schemaVersion(1)
                .producer("trip")
                .occurredAt(Instant.now().toString())
                .correlationId(correlationId != null ? correlationId : eventId)
                .causationId(correlationId != null ? correlationId : eventId)
                .aggregateId(aggregateId)
                .aggregateVersion(aggregateVersion)
                .data(data)
                .build();
    }
}
