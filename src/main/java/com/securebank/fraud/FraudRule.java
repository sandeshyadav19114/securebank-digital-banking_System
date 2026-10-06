package com.securebank.fraud;

import java.util.Optional;

/** Add a new rule by implementing this interface and annotating it with @Component. */
public interface FraudRule {
    Optional<RuleHit> evaluate(TransactionEvent event);
}
