package com.securebank.ledger;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** One leg of a double-entry posting. Immutable and append-only: corrections are made with reversing entries. */
@Entity
@Immutable
@Table(name = "ledger_entries", indexes = {
        @Index(name = "idx_ledger_account_date", columnList = "account_number,entry_date"),
        @Index(name = "idx_ledger_txn", columnList = "transaction_id")})
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private Long transactionId;

    @Column(name = "transaction_reference", nullable = false, updatable = false, length = 40)
    private String transactionReference;

    @Column(name = "account_number", nullable = false, updatable = false, length = 20)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, updatable = false, length = 10)
    private EntryType entryType;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "counterparty_account", updatable = false, length = 20)
    private String counterpartyAccount;

    @Column(updatable = false, length = 160)
    private String narration;

    @Column(name = "entry_date", nullable = false, updatable = false)
    private LocalDate entryDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
