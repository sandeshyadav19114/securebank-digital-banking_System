package com.securebank.audit;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** Append-only audit trail. Rows are never updated or deleted by the application. */
@Entity
@Immutable
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_actor", columnList = "actor"),
        @Index(name = "idx_audit_entity", columnList = "entity_type,entity_id")})
@Getter
@NoArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 80)
    private String actor;

    @Column(nullable = false, updatable = false, length = 60)
    private String action;

    @Column(name = "entity_type", updatable = false, length = 40)
    private String entityType;

    @Column(name = "entity_id", updatable = false, length = 80)
    private String entityId;

    @Column(nullable = false, updatable = false, length = 20)
    private String outcome;

    @Column(updatable = false, length = 1000)
    private String details;

    @Column(updatable = false, length = 64)
    private String clientIp;

    @Column(updatable = false, length = 64)
    private String requestId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public AuditLog(String actor, String action, String entityType, String entityId, String outcome,
                    String details, String clientIp, String requestId) {
        this.actor = actor;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.outcome = outcome;
        this.details = details;
        this.clientIp = clientIp;
        this.requestId = requestId;
        this.createdAt = Instant.now();
    }
}
