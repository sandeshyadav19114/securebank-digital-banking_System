package com.securebank.ledger;

import com.securebank.account.Account;
import com.securebank.account.AccountRepository;
import com.securebank.audit.AuditService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Daily reconciliation. Verifies three invariants of the double-entry system:
 *  1. total debits == total credits for the business date
 *  2. every transaction's debit legs == credit legs
 *  3. for every account: balance == sum(credits) - sum(debits) over the whole ledger
 * Computation runs in one REPEATABLE_READ read-only transaction so it sees a consistent snapshot while
 * transfers continue; the report row is saved afterwards.
 */
@Slf4j
@Service
public class ReconciliationService {

    private record Result(BigDecimal debits, BigDecimal credits, List<Long> unbalanced, List<String> mismatched) {}

    private final LedgerRepository ledger;
    private final AccountRepository accounts;
    private final ReconciliationRepository reports;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate snapshotTx;

    public ReconciliationService(LedgerRepository ledger, AccountRepository accounts, ReconciliationRepository reports,
                                 AuditService audit, Clock clock, PlatformTransactionManager txManager) {
        this.ledger = ledger;
        this.accounts = accounts;
        this.reports = reports;
        this.audit = audit;
        this.clock = clock;
        this.snapshotTx = new TransactionTemplate(txManager);
        this.snapshotTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshotTx.setReadOnly(true);
    }

    public ReconciliationReport run(LocalDate businessDate) {
        Result r = snapshotTx.execute(status -> compute(businessDate));
        boolean ok = r.debits().compareTo(r.credits()) == 0 && r.unbalanced().isEmpty() && r.mismatched().isEmpty();
        String details = ok ? "All invariants hold" : ("unbalancedTxns=" + r.unbalanced() + " mismatchedAccounts=" + r.mismatched());
        ReconciliationReport report = reports.save(ReconciliationReport.builder()
                .businessDate(businessDate)
                .status(ok ? ReconciliationReport.Status.BALANCED : ReconciliationReport.Status.MISMATCH)
                .totalDebits(r.debits())
                .totalCredits(r.credits())
                .unbalancedTransactions(r.unbalanced().size())
                .mismatchedAccounts(r.mismatched().size())
                .details(details.length() > 2000 ? details.substring(0, 2000) : details)
                .createdAt(clock.instant())
                .build());
        if (ok) {
            log.info("reconciliation date={} status=BALANCED debits={} credits={}", businessDate, r.debits(), r.credits());
        } else {
            log.error("reconciliation date={} status=MISMATCH {}", businessDate, details);
        }
        audit.record("system", "RECONCILIATION", "Ledger", businessDate.toString(), report.getStatus().name(), details);
        return report;
    }

    public Page<ReconciliationReport> reports(Pageable pageable) {
        return reports.findAllByOrderByIdDesc(pageable);
    }

    private Result compute(LocalDate date) {
        BigDecimal debits = nz(ledger.sumForDate(EntryType.DEBIT, date));
        BigDecimal credits = nz(ledger.sumForDate(EntryType.CREDIT, date));
        List<Long> unbalanced = ledger.findUnbalancedTransactions(date);

        Map<String, BigDecimal> net = new HashMap<>();
        for (Object[] row : ledger.totalsByAccount()) {
            BigDecimal sum = (BigDecimal) row[2];
            net.merge((String) row[0], row[1] == EntryType.CREDIT ? sum : sum.negate(), BigDecimal::add);
        }
        List<String> mismatched = new ArrayList<>();
        for (Account a : accounts.findAll()) {
            BigDecimal expected = net.getOrDefault(a.getAccountNumber(), BigDecimal.ZERO);
            if (expected.compareTo(a.getBalance()) != 0) {
                mismatched.add(a.getAccountNumber());
            }
        }
        return new Result(debits, credits, unbalanced, mismatched);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
