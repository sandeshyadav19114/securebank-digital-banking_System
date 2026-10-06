package com.securebank.fraud;

import com.securebank.audit.AuditService;
import com.securebank.common.BusinessException;
import com.securebank.common.ErrorCode;
import com.securebank.fraud.FraudDtos.AlertResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class FraudDetectionService {

    private final List<FraudRule> rules;
    private final FraudAlertRepository alerts;
    private final AuditService audit;

    /** Every rule always runs (stateful rules must observe every event); alerts are de-duplicated per (txn, rule). */
    @Transactional
    public List<FraudAlert> analyze(TransactionEvent event) {
        List<FraudAlert> created = new ArrayList<>();
        for (FraudRule rule : rules) {
            Optional<RuleHit> hit = rule.evaluate(event);
            if (hit.isEmpty() || alerts.existsByTransactionReferenceAndRuleCode(event.reference(), hit.get().ruleCode())) {
                continue;
            }
            FraudAlert alert = new FraudAlert();
            alert.setTransactionReference(event.reference());
            alert.setAccountNumber(event.fromAccount());
            alert.setCustomerId(event.customerId());
            alert.setRuleCode(hit.get().ruleCode());
            alert.setSeverity(hit.get().severity());
            alert.setReason(hit.get().reason());
            created.add(alerts.save(alert));
            log.warn("fraud_alert rule={} severity={} txn={} account={} reason=\"{}\"", hit.get().ruleCode(),
                    hit.get().severity(), event.reference(), event.fromAccount(), hit.get().reason());
        }
        return created;
    }

    @Transactional(readOnly = true)
    public Page<AlertResponse> list(AlertStatus status, Pageable pageable) {
        Page<FraudAlert> page = status == null ? alerts.findAllByOrderByIdDesc(pageable)
                : alerts.findByStatusOrderByIdDesc(status, pageable);
        return page.map(AlertResponse::from);
    }

    @Transactional
    public AlertResponse markReviewed(Long id, Long adminId) {
        FraudAlert alert = alerts.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Alert not found"));
        alert.setStatus(AlertStatus.REVIEWED);
        alerts.save(alert);
        audit.record("admin:" + adminId, "FRAUD_ALERT_REVIEWED", "FraudAlert", String.valueOf(id), "SUCCESS", null);
        return AlertResponse.from(alert);
    }
}
