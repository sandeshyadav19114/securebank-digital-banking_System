package com.securebank.fraud;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudAlertRepository extends JpaRepository<FraudAlert, Long> {
    boolean existsByTransactionReferenceAndRuleCode(String reference, String ruleCode);

    Page<FraudAlert> findByStatusOrderByIdDesc(AlertStatus status, Pageable pageable);

    Page<FraudAlert> findAllByOrderByIdDesc(Pageable pageable);
}
