package com.securebank.fraud;

import com.securebank.config.FraudProperties;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** "Impossible travel": the customer transacts from two different countries within a short window. */
@Component
@RequiredArgsConstructor
public class GeoInconsistencyRule implements FraudRule {

    private final StringRedisTemplate redis;
    private final FraudProperties props;

    @Override
    public Optional<RuleHit> evaluate(TransactionEvent e) {
        if (e.country() == null || e.country().isBlank()) {
            return Optional.empty();
        }
        String key = "fraud:geo:" + e.customerId();
        long now = e.occurredAt().toEpochMilli();
        String previous = redis.opsForValue().get(key);
        redis.opsForValue().set(key, e.country() + "|" + now, Duration.ofHours(24));
        if (previous == null || !previous.contains("|")) {
            return Optional.empty();
        }
        String[] parts = previous.split("\\|");
        long elapsedMs = now - Long.parseLong(parts[1]);
        if (!parts[0].equals(e.country()) && elapsedMs >= 0 && elapsedMs <= props.geoWindowMinutes() * 60_000L) {
            return Optional.of(new RuleHit("GEO_INCONSISTENT", Severity.HIGH,
                    "Country changed " + parts[0] + " -> " + e.country() + " within " + (elapsedMs / 60_000) + " min"));
        }
        return Optional.empty();
    }
}
