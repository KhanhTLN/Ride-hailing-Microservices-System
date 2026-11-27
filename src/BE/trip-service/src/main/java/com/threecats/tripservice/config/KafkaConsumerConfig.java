package com.threecats.tripservice.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            CommonErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    @Bean
    public CommonErrorHandler errorHandler(KafkaTemplate<String, String> kafkaTemplate,com.fasterxml.jackson.databind.ObjectMapper mapper) {
        org.springframework.kafka.listener.ConsumerRecordRecoverer recoverer=(record,ex) -> {
            String group=record.topic().contains("payment") ? "trip-payment-v1" : "trip-matching-v1";
            Map<String,Object> dead=new java.util.LinkedHashMap<>();
            dead.put("original_topic",record.topic()); dead.put("partition",record.partition()); dead.put("offset",Long.toString(record.offset()));
            dead.put("consumer_group",group); dead.put("attempts",6); dead.put("error_code","PROCESSING_FAILED"); dead.put("failed_at",java.time.Instant.now().toString());
            String raw=String.valueOf(record.value());
            try { var value=mapper.readTree(raw); if(!value.isObject()) throw new IllegalArgumentException(); dead.put("original_message",value); }
            catch(Exception ignored) { dead.put("original_bytes_base64",java.util.Base64.getEncoder().encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
            try { kafkaTemplate.send(record.topic()+".dlq."+group,record.partition(),String.valueOf(record.key()),mapper.writeValueAsString(dead)).get(5,java.util.concurrent.TimeUnit.SECONDS); }
            catch(Exception e) { throw new IllegalStateException("DLQ delivery not acknowledged",e); }
        };
        org.springframework.util.backoff.BackOff backoff=() -> new org.springframework.util.backoff.BackOffExecution() {
            private int index;
            private final long[] delays={1000,5000,15000,30000,60000};
            public long nextBackOff() { return index<delays.length ? delays[index++] : STOP; }
        };
        return new DefaultErrorHandler(recoverer,backoff);
    }
}
