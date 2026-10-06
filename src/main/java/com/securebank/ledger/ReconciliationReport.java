package com.securebank.ledger;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "reconciliation_reports")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconciliationReport {

    public enum Status { BALANCED, MISMATCH }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status;

    @Column(name = "total_debits", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalDebits;

    @Column(name = "total_credits", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalCredits;

    @Column(name = "unbalanced_transactions", nullable = false)
    private int unbalancedTransactions;

    @Column(name = "mismatched_accounts", nullable = false)
    private int mismatchedAccounts;

    @Column(length = 2000)
    private String details;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
