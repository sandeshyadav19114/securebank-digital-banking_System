package com.securebank.fraud;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes to Kafka only after the DB transaction has committed, so the fraud service never sees a
 * transfer that was rolled back. (Production hardening: replace with a transactional outbox table.)
 */
@Slf4j
@Component
public class TransactionEventPublisher {

    private final KafkaTemplate<String, TransactionEvent> kafka;
    private final String topic;

    public TransactionEventPublisher(KafkaTemplate<String, TransactionEvent> kafka,
                                     @Value("${securebank.kafka.transactions-topic}") String topic) {
        this.kafka = kafka;
        this.topic = topic;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransactionCompleted(TransactionEvent event) {
        kafka.send(topic, event.fromAccount(), event).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("kafka_publish_failed txn={} error={}", event.reference(), ex.toString());
            }
        });
    }
}
