package com.securebank.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.securebank.audit.AuditService;
import com.securebank.common.BusinessException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class FraudDetectionServiceTest {

    final FraudAlertRepository repo = mock(FraudAlertRepository.class);
    final AuditService audit = mock(AuditService.class);
    final FraudRule hit = e -> Optional.of(new RuleHit("R1", Severity.HIGH, "why"));
    final FraudRule miss = e -> Optional.empty();
    final TransactionEvent event = new TransactionEvent("TXN-9", "SB1", "SB2", BigDecimal.TEN, 3L, "IN", "ip", Instant.now());

    @Test
    void savesAlertForHit() {
        when(repo.save(any(FraudAlert.class))).thenAnswer(i -> i.getArgument(0));
        List<FraudAlert> out = new FraudDetectionService(List.of(hit, miss), repo, audit).analyze(event);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getRuleCode()).isEqualTo("R1");
        assertThat(out.get(0).getTransactionReference()).isEqualTo("TXN-9");
    }

    @Test
    void duplicateDeliveryDoesNotCreateSecondAlert() {
        when(repo.existsByTransactionReferenceAndRuleCode("TXN-9", "R1")).thenReturn(true);
        assertThat(new FraudDetectionService(List.of(hit), repo, audit).analyze(event)).isEmpty();
        verify(repo, never()).save(any());
    }

    @Test
    void everyRuleIsEvaluatedEvenAfterAHit() {
        FraudRule second = mock(FraudRule.class);
        when(second.evaluate(event)).thenReturn(Optional.empty());
        when(repo.save(any(FraudAlert.class))).thenAnswer(i -> i.getArgument(0));
        new FraudDetectionService(List.of(hit, second), repo, audit).analyze(event);
        verify(second).evaluate(event);
    }

    @Test
    void listAndReview() {
        FraudAlert a = new FraudAlert();
        a.setSeverity(Severity.LOW);
        when(repo.findByStatusOrderByIdDesc(AlertStatus.OPEN, PageRequest.of(0, 5))).thenReturn(new PageImpl<>(List.of(a)));
        when(repo.findAllByOrderByIdDesc(PageRequest.of(0, 5))).thenReturn(new PageImpl<>(List.of(a, a)));
        when(repo.findById(1L)).thenReturn(Optional.of(a));
        FraudDetectionService s = new FraudDetectionService(List.of(), repo, audit);

        assertThat(s.list(AlertStatus.OPEN, PageRequest.of(0, 5)).getContent()).hasSize(1);
        assertThat(s.list(null, PageRequest.of(0, 5)).getContent()).hasSize(2);
        assertThat(s.markReviewed(1L, 9L).status()).isEqualTo(AlertStatus.REVIEWED);
        org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class, () -> s.markReviewed(2L, 9L));
    }
}
