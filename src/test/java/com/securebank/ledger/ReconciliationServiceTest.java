package com.securebank.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.securebank.account.Account;
import com.securebank.account.AccountRepository;
import com.securebank.audit.AuditService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class ReconciliationServiceTest {

    LedgerRepository ledger = mock(LedgerRepository.class);
    AccountRepository accounts = mock(AccountRepository.class);
    ReconciliationRepository reports = mock(ReconciliationRepository.class);
    AuditService audit = mock(AuditService.class);
    PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    Clock clock = Clock.fixed(Instant.parse("2026-02-01T00:30:00Z"), ZoneOffset.UTC);
    ReconciliationService service;
    final LocalDate date = LocalDate.of(2026, 1, 31);

    @BeforeEach
    void setUp() {
        service = new ReconciliationService(ledger, accounts, reports, audit, clock, txManager);
        when(reports.save(any(ReconciliationReport.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Account acct(String number, String balance) {
        Account a = new Account();
        a.setAccountNumber(number);
        a.setBalance(new BigDecimal(balance));
        return a;
    }

    private void ledgerState(String debits, String credits) {
        when(ledger.sumForDate(EntryType.DEBIT, date)).thenReturn(new BigDecimal(debits));
        when(ledger.sumForDate(EntryType.CREDIT, date)).thenReturn(new BigDecimal(credits));
        when(ledger.findUnbalancedTransactions(date)).thenReturn(List.of());
        // GL: credits 0, debits 500 => -500 ; SB1: credits 500, debits 100 => 400 ; SB2: credits 100 => 100
        when(ledger.totalsByAccount()).thenReturn(List.of(
                new Object[]{"GL1", EntryType.DEBIT, new BigDecimal("500.00")},
                new Object[]{"SB1", EntryType.CREDIT, new BigDecimal("500.00")},
                new Object[]{"SB1", EntryType.DEBIT, new BigDecimal("100.00")},
                new Object[]{"SB2", EntryType.CREDIT, new BigDecimal("100.00")}));
    }

    @Test
    void consistentLedgerIsBalanced() {
        ledgerState("600.00", "600.00");
        when(accounts.findAll()).thenReturn(List.of(acct("GL1", "-500.00"), acct("SB1", "400.00"), acct("SB2", "100.00")));

        ReconciliationReport r = service.run(date);

        assertThat(r.getStatus()).isEqualTo(ReconciliationReport.Status.BALANCED);
        assertThat(r.getMismatchedAccounts()).isZero();
        verify(audit).record(org.mockito.ArgumentMatchers.eq("system"), org.mockito.ArgumentMatchers.eq("RECONCILIATION"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("2026-01-31"),
                org.mockito.ArgumentMatchers.eq("BALANCED"), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void tamperedBalanceIsDetected() {
        ledgerState("600.00", "600.00");
        when(accounts.findAll()).thenReturn(List.of(acct("GL1", "-500.00"), acct("SB1", "999.00"), acct("SB2", "100.00")));
        ReconciliationReport r = service.run(date);
        assertThat(r.getStatus()).isEqualTo(ReconciliationReport.Status.MISMATCH);
        assertThat(r.getMismatchedAccounts()).isEqualTo(1);
        assertThat(r.getDetails()).contains("SB1");
    }

    @Test
    void debitCreditTotalsDifferingIsMismatch() {
        ledgerState("600.00", "599.00");
        when(accounts.findAll()).thenReturn(List.of(acct("GL1", "-500.00"), acct("SB1", "400.00"), acct("SB2", "100.00")));
        assertThat(service.run(date).getStatus()).isEqualTo(ReconciliationReport.Status.MISMATCH);
    }

    @Test
    void unbalancedTransactionIsMismatchAndEmptyLedgerIsBalanced() {
        when(ledger.sumForDate(any(), any())).thenReturn(null);
        when(ledger.findUnbalancedTransactions(date)).thenReturn(List.of(42L));
        when(ledger.totalsByAccount()).thenReturn(List.of());
        when(accounts.findAll()).thenReturn(List.of());
        ReconciliationReport r = service.run(date);
        assertThat(r.getUnbalancedTransactions()).isEqualTo(1);
        assertThat(r.getTotalDebits()).isEqualByComparingTo("0");

        when(ledger.findUnbalancedTransactions(date)).thenReturn(List.of());
        assertThat(service.run(date).getStatus()).isEqualTo(ReconciliationReport.Status.BALANCED);
    }
}
