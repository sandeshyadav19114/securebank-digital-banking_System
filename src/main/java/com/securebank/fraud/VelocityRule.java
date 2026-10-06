package com.securebank.fraud;

import com.securebank.config.FraudProperties;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

/**
 * Sliding-window counter in a Redis sorted set (member = txn reference, score = event time).
 * Using the reference as member makes Kafka redelivery idempotent.
 */
@Component
@RequiredArgsConstructor
public class VelocityRule implements FraudRule {

    private final StringRedisTemplate redis;
    private final FraudProperties props;

    @Override
    public Optional<RuleHit> evaluate(TransactionEvent e) {
        String key = "fraud:velocity:" + e.fromAccount();
        long now = e.occurredAt().toEpochMilli();
        long cutoff = now - props.velocityWindowSeconds() * 1000;
        ZSetOperations<String, String> zset = redis.opsForZSet();
        zset.add(key, e.reference(), now);
        zset.removeRangeByScore(key, 0, cutoff);
        redis.expire(key, Duration.ofSeconds(props.velocityWindowSeconds() * 2));
        Long count = zset.zCard(key);
        if (count != null && count > props.velocityMaxTransactions()) {
            return Optional.of(new RuleHit("VELOCITY", Severity.HIGH,
                    count + " transfers in " + props.velocityWindowSeconds() + "s (max " + props.velocityMaxTransactions() + ")"));
        }
        return Optional.empty();
    }
}
