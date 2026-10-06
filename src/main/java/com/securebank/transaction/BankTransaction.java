package com.securebank.transaction;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** Immutable transaction header. Only completed transactions are persisted; failures roll back and are audited. */
@Entity
@Immutable
@Table(name = "transactions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_txn_reference", columnNames = "reference"),
                @UniqueConstraint(name = "uk_txn_idempotency", columnNames = "idempotency_key")},
        indexes = {
                @Index(name = "idx_txn_from", columnList = "from_account"),
                @Index(name = "idx_txn_to", columnList = "to_account")})
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class BankTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 40)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 12)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 12)
    private TransactionStatus status;

    @Column(name = "from_account", nullable = false, updatable = false, length = 20)
    private String fromAccount;

    @Column(name = "to_account", nullable = false, updatable = false, length = 20)
    private String toAccount;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(updatable = false, length = 140)
    private String remarks;

    /** "{customerId}:{Idempotency-Key header}". UNIQUE => a retried request can never debit twice. */
    @Column(name = "idempotency_key", updatable = false, length = 120)
    private String idempotencyKey;

    @Column(name = "initiated_by", updatable = false)
    private Long initiatedBy;

    @Column(name = "ip_address", updatable = false, length = 64)
    private String ipAddress;

    @Column(updatable = false, length = 2)
    private String country;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
