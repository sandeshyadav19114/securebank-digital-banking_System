package com.securebank.audit;

import org.springframework.data.repository.Repository;

/** Deliberately exposes only "save": the audit trail is append-only. */
public interface AuditLogRepository extends Repository<AuditLog, Long> {
    <S extends AuditLog> S save(S entity);
}
