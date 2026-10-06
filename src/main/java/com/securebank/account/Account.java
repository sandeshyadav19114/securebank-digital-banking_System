package com.securebank.account;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "accounts",
        uniqueConstraints = @UniqueConstraint(name = "uk_account_number", columnNames = "account_number"),
        indexes = @Index(name = "idx_account_customer", columnList = "customer_id"))
@Getter
@Setter
@NoArgsConstructor
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_number", nullable = false, updatable = false, length = 20)
    private String accountNumber;

    /** null for bank-owned general-ledger (system) accounts. */
    @Column(name = "customer_id")
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "overdraft_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal overdraftLimit = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(name = "system_account", nullable = false)
    private boolean systemAccount = false;

    @Column(name = "interest_rate", precision = 5, scale = 2)
    private BigDecimal interestRate;

    @Column(name = "maturity_date")
    private LocalDate maturityDate;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** Optimistic guard in addition to the pessimistic row locks used on money movement. */
    @Version
    private long version;

    @PrePersist
    void onCreate() {
        this.openedAt = Instant.now();
    }

    public BigDecimal getAvailableBalance() {
        return balance.add(overdraftLimit);
    }
}
