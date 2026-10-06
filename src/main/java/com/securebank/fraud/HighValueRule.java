package com.securebank.fraud;

import com.securebank.config.FraudProperties;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HighValueRule implements FraudRule {

    private final FraudProperties props;

    @Override
    public Optional<RuleHit> evaluate(TransactionEvent e) {
        if (e.amount().compareTo(props.highValueThreshold()) < 0) {
            return Optional.empty();
        }
        Severity severity = e.amount().compareTo(props.highValueThreshold().multiply(java.math.BigDecimal.valueOf(2))) >= 0
                ? Severity.HIGH : Severity.MEDIUM;
        return Optional.of(new RuleHit("HIGH_VALUE", severity,
                "Amount " + e.amount() + " >= threshold " + props.highValueThreshold()));
    }
}
