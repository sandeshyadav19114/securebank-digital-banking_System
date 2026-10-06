package com.securebank.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.securebank.config.FraudProperties;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FraudRulesTest {

    @Mock StringRedisTemplate redis;
    @Mock ZSetOperations<String, String> zset;
    @Mock ValueOperations<String, String> values;

    final FraudProperties props = new FraudProperties(new BigDecimal("500000"), 5, 60, 30);
    final Instant now = Instant.parse("2026-01-01T10:00:00Z");

    @BeforeEach
    void setUp() {
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForValue()).thenReturn(values);
    }

    private TransactionEvent event(String amount, String country, Instant at) {
        return new TransactionEvent("TXN-1", "SB1", "SB2", new BigDecimal(amount), 7L, country, "1.1.1.1", at);
    }

    @Test
    void highValueBelowThresholdIsClean() {
        assertThat(new HighValueRule(props).evaluate(event("499999.99", null, now))).isEmpty();
    }

    @Test
    void highValueAtThresholdIsMediumAndDoubleIsHigh() {
        HighValueRule rule = new HighValueRule(props);
        assertThat(rule.evaluate(event("500000", null, now))).get().extracting(RuleHit::severity).isEqualTo(Severity.MEDIUM);
        assertThat(rule.evaluate(event("1000000", null, now))).get().extracting(RuleHit::severity).isEqualTo(Severity.HIGH);
    }

    @Test
    void velocityFlagsWhenCountExceedsMax() {
        when(zset.zCard("fraud:velocity:SB1")).thenReturn(6L);
        assertThat(new VelocityRule(redis, props).evaluate(event("10", null, now)))
                .get().extracting(RuleHit::ruleCode).isEqualTo("VELOCITY");
        verify(zset).add("fraud:velocity:SB1", "TXN-1", now.toEpochMilli());
        verify(zset).removeRangeByScore("fraud:velocity:SB1", 0, now.toEpochMilli() - 60_000);
    }

    @Test
    void velocityAtLimitIsClean() {
        when(zset.zCard(anyString())).thenReturn(5L);
        assertThat(new VelocityRule(redis, props).evaluate(event("10", null, now))).isEmpty();
    }

    @Test
    void geoIgnoredWithoutCountry() {
        assertThat(new GeoInconsistencyRule(redis, props).evaluate(event("10", null, now))).isEmpty();
    }

    @Test
    void geoFirstTransactionIsClean() {
        when(values.get("fraud:geo:7")).thenReturn(null);
        assertThat(new GeoInconsistencyRule(redis, props).evaluate(event("10", "IN", now))).isEmpty();
    }

    @Test
    void geoFlagsCountryChangeInsideWindow() {
        when(values.get("fraud:geo:7")).thenReturn("IN|" + now.minusSeconds(600).toEpochMilli());
        assertThat(new GeoInconsistencyRule(redis, props).evaluate(event("10", "US", now)))
                .get().extracting(RuleHit::ruleCode).isEqualTo("GEO_INCONSISTENT");
    }

    @Test
    void geoIgnoresCountryChangeOutsideWindowOrSameCountry() {
        GeoInconsistencyRule rule = new GeoInconsistencyRule(redis, props);
        when(values.get("fraud:geo:7")).thenReturn("IN|" + now.minusSeconds(3 * 3600).toEpochMilli());
        assertThat(rule.evaluate(event("10", "US", now))).isEmpty();
        when(values.get("fraud:geo:7")).thenReturn("IN|" + now.minusSeconds(60).toEpochMilli());
        assertThat(rule.evaluate(event("10", "IN", now))).isEmpty();
    }
}
