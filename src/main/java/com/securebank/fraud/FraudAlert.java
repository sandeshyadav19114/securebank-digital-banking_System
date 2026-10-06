package com.securebank.fraud;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "fraud_alerts",
        uniqueConstraints = @UniqueConstraint(name = "uk_alert_txn_rule", columnNames = {"transaction_reference", "rule_code"}))
@Getter
@Setter
@NoArgsConstructor
public class FraudAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_reference", nullable = false, length = 40)
    private String transactionReference;

    @Column(name = "account_number", nullable = false, length = 20)
    private String accountNumber;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "rule_code", nullable = false, length = 40)
    private String ruleCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severity severity;

    @Column(length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AlertStatus status = AlertStatus.OPEN;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
