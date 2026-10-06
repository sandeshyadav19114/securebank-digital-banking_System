package com.securebank.ledger;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs nightly. A Redis lock ensures only one pod executes it when the service is scaled out. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "securebank.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationJob {

    private final ReconciliationService service;
    private final StringRedisTemplate redis;
    private final Clock clock;

    @Scheduled(cron = "${securebank.reconciliation.cron}", zone = "UTC")
    public void reconcileYesterday() {
        LocalDate date = LocalDate.now(clock).minusDays(1);
        Boolean acquired = redis.opsForValue().setIfAbsent("lock:reconciliation:" + date, "1", Duration.ofHours(2));
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("reconciliation for {} is already handled by another instance", date);
            return;
        }
        service.run(date);
    }
}
