package com.threecats.tripservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threecats.tripservice.service.TripService;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;
import java.util.*;

@Component @RequiredArgsConstructor
public class TripKafkaConsumer {
    private final TripService service;
    private final ObjectMapper mapper;
    @KafkaListener(topics="ds03.matching.events.v1",groupId="trip-matching-v1")
    public void matching(ConsumerRecord<String,String> record) { consume(record,"matching",Set.of("DriverReserved","MatchingFailed")); }
    @KafkaListener(topics="ds03.payment.events.v1",groupId="trip-payment-v1")
    public void payment(ConsumerRecord<String,String> record) { consume(record,"payment",Set.of("PaymentSucceeded","PaymentFailed")); }
    private void consume(ConsumerRecord<String,String> record,String producer,Set<String> allowed) {
        try {
            if(record.value().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536) throw new IllegalArgumentException("Message too large");
            var event=mapper.readTree(record.value());
            requireFields(event,Set.of("event_id","event_type","schema_version","producer","occurred_at","correlation_id","causation_id","aggregate_id","aggregate_version","data"));
            if(!producer.equals(event.path("producer").asText()) || !event.path("schema_version").isIntegralNumber() || event.path("schema_version").asInt()!=1
                || !event.path("aggregate_version").isIntegralNumber() || !event.path("aggregate_version").canConvertToInt()
                || event.path("aggregate_version").asLong()<1 || !allowed.contains(event.path("event_type").asText())) throw new IllegalArgumentException("Invalid event envelope");
            for(String field:List.of("event_id","aggregate_id","correlation_id","causation_id")) TripService.uuid(event,field);
            OffsetDateTime.parse(event.path("occurred_at").asText());
            if(!event.path("aggregate_id").asText().equals(record.key())) throw new IllegalArgumentException("Invalid Kafka trip key");
            validateData(event.path("event_type").asText(),event.path("data"));
            service.consume(event);
        } catch(RuntimeException e) { throw e; }
        catch(Exception e) { throw new IllegalArgumentException("Invalid event JSON",e); }
    }
    private void requireFields(com.fasterxml.jackson.databind.JsonNode node,Set<String> expected) {
        if(!node.isObject()) throw new IllegalArgumentException("Object required");
        Set<String> actual=new HashSet<>(); node.fieldNames().forEachRemaining(actual::add);
        if(!actual.equals(expected)) throw new IllegalArgumentException("Event fields do not match the contract");
    }
    private void validateData(String type,com.fasterxml.jackson.databind.JsonNode data) {
        if(type.equals("DriverReserved")) {
            requireFields(data,Set.of("trip_id","attempt_id","driver_id","reservation_id","expires_at"));
            for(String field:List.of("trip_id","attempt_id","driver_id","reservation_id")) TripService.uuid(data,field);
            OffsetDateTime.parse(data.path("expires_at").asText());
        } else if(type.equals("MatchingFailed")) {
            requireFields(data,Set.of("trip_id","attempt_id","reason")); TripService.uuid(data,"trip_id"); TripService.uuid(data,"attempt_id");
            if(!Set.of("NO_DRIVER","DEADLINE_EXCEEDED","DEPENDENCY_UNAVAILABLE").contains(data.path("reason").asText())) throw new IllegalArgumentException("Invalid failure reason");
        } else {
            Set<String> fields=new HashSet<>(Set.of("trip_id","passenger_id","payment_id","attempt_number","amount"));
            if(type.equals("PaymentFailed")) fields.add("failure_code");
            requireFields(data,fields);
            for(String field:List.of("trip_id","passenger_id","payment_id")) TripService.uuid(data,field);
            if(!data.path("attempt_number").isIntegralNumber() || !data.path("attempt_number").canConvertToInt() || data.path("attempt_number").asInt()<1) throw new IllegalArgumentException("Invalid payment attempt");
            var amount=data.path("amount"); requireFields(amount,Set.of("amount","currency"));
            if(!amount.path("amount").isTextual() || !amount.path("amount").asText().matches("^(0|[1-9][0-9]*)$") || !amount.path("currency").asText().equals("VND")) throw new IllegalArgumentException("Invalid money");
            if(type.equals("PaymentFailed") && !Set.of("SIMULATED_DECLINE","PROCESSING_ERROR").contains(data.path("failure_code").asText())) throw new IllegalArgumentException("Invalid payment failure code");
        }
    }
}
