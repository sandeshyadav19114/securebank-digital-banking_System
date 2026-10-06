package com.securebank.fraud;

import java.math.BigDecimal;
import java.time.Instant;

/** Published to Kafka (key = fromAccount, so one account's events stay ordered within a partition). */
public record TransactionEvent(String reference, String fromAccount, String toAccount, BigDecimal amount,
                               Long customerId, String country, String ipAddress, Instant occurredAt) {}
