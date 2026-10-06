package com.securebank.fraud;

import java.time.Instant;

public final class FraudDtos {
    private FraudDtos() {}

    public record AlertResponse(Long id, String transactionReference, String accountNumber, Long customerId,
                                String ruleCode, Severity severity, String reason, AlertStatus status, Instant createdAt) {
        public static AlertResponse from(FraudAlert a) {
            return new AlertResponse(a.getId(), a.getTransactionReference(), a.getAccountNumber(), a.getCustomerId(),
                    a.getRuleCode(), a.getSeverity(), a.getReason(), a.getStatus(), a.getCreatedAt());
        }
    }
}
