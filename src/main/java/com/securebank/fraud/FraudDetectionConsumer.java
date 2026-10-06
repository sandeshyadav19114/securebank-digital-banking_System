package com.securebank.fraud;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Near-real-time consumer. Scale horizontally up to the topic's partition count (3). */
@Component
@RequiredArgsConstructor
public class FraudDetectionConsumer {

    private final FraudDetectionService service;

    @KafkaListener(topics = "${securebank.kafka.transactions-topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void onMessage(TransactionEvent event) {
        service.analyze(event);
    }
}
