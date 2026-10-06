package com.securebank.beneficiary;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "beneficiaries",
        uniqueConstraints = @UniqueConstraint(name = "uk_beneficiary", columnNames = {"customer_id", "account_number"}))
@Getter
@Setter
@NoArgsConstructor
public class Beneficiary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "account_number", nullable = false, length = 20)
    private String accountNumber;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 60)
    private String nickname;

    @Column(name = "bank_name", nullable = false, length = 60)
    private String bankName = "SecureBank";

    /** Soft delete keeps the audit history intact. */
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
