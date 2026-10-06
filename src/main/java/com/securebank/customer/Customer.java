package com.securebank.customer;

import com.securebank.security.AesAttributeConverter;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "customers", uniqueConstraints = @UniqueConstraint(name = "uk_customer_email", columnNames = "email"))
@Getter
@Setter
@NoArgsConstructor
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String fullName;

    @Column(nullable = false, length = 160)
    private String email;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role = Role.CUSTOMER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KycStatus kycStatus = KycStatus.NOT_SUBMITTED;

    @Column(length = 255)
    private String kycRejectionReason;

    /* ---- PII: AES-256-GCM encrypted at rest ---- */
    @Convert(converter = AesAttributeConverter.class)
    @Column(length = 512)
    private String panNumber;

    @Convert(converter = AesAttributeConverter.class)
    @Column(length = 512)
    private String aadhaarNumber;

    @Convert(converter = AesAttributeConverter.class)
    @Column(length = 1024)
    private String address;

    @Column(nullable = false)
    private boolean mfaEnabled = true;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private int failedLoginAttempts = 0;

    private Instant lockedUntil;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
