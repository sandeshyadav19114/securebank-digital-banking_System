package com.securebank.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a structured log line (logger "AUDIT") and an immutable DB row.
 * Uses REQUIRES_NEW so failed business transactions are still audited.
 */
@Slf4j(topic = "AUDIT")
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String actor, String action, String entityType, String entityId,
                       String outcome, String details) {
        String ip = MDC.get("clientIp");
        String requestId = MDC.get("requestId");
        log.info("audit action={} actor={} entityType={} entityId={} outcome={} ip={} details=\"{}\"",
                action, actor, entityType, entityId, outcome, ip, details);
        repository.save(new AuditLog(actor, action, entityType, entityId, outcome,
                truncate(details), ip, requestId));
    }

    private static String truncate(String s) {
        return s == null ? null : (s.length() > 1000 ? s.substring(0, 1000) : s);
    }
}
