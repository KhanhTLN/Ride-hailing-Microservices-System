package com.threecats.tripservice.config;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConsumerConfig {

    @Bean
    public CommonErrorHandler errorHandler(KafkaOperations<Object, Object> kafkaOperations) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaOperations,
                (record, ex) -> {
                    String targetTopic = record.topic() + ".dlq.trip-matching-v1";
                    if (record.topic().contains("payment")) {
                        targetTopic = record.topic() + ".dlq.trip-payment-v1";
                    }
                    return new TopicPartition(targetTopic, record.partition());
                });

        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3));
    }
}
