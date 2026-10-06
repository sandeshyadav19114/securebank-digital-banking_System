package com.securebank.statement;

import com.securebank.account.AccountService;
import com.securebank.common.BusinessException;
import com.securebank.common.ErrorCode;
import com.securebank.ledger.EntryType;
import com.securebank.ledger.LedgerEntry;
import com.securebank.ledger.LedgerRepository;
import com.securebank.statement.StatementDtos.StatementLine;
import com.securebank.statement.StatementDtos.StatementResponse;
import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Statements are derived purely from the immutable ledger. */
@Service
@RequiredArgsConstructor
public class StatementService {

    static final long MAX_RANGE_DAYS = 366;

    private final LedgerRepository ledger;
    private final AccountService accountService;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatementResponse generate(String accountNumber, Long customerId, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "'from' must not be after 'to'");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Maximum statement range is " + MAX_RANGE_DAYS + " days");
        }
        accountService.getOwned(accountNumber, customerId);

        BigDecimal opening = nz(ledger.sumBefore(accountNumber, EntryType.CREDIT, from))
                .subtract(nz(ledger.sumBefore(accountNumber, EntryType.DEBIT, from)));
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        List<StatementLine> lines = new ArrayList<>();
        for (LedgerEntry e : ledger.findByAccountNumberAndEntryDateBetweenOrderByIdAsc(accountNumber, from, to)) {
            boolean isDebit = e.getEntryType() == EntryType.DEBIT;
            if (isDebit) debits = debits.add(e.getAmount()); else credits = credits.add(e.getAmount());
            lines.add(new StatementLine(e.getEntryDate(), e.getTransactionReference(), e.getEntryType(),
                    e.getCounterpartyAccount(), e.getNarration(),
                    isDebit ? e.getAmount() : null, isDebit ? null : e.getAmount(), e.getBalanceAfter()));
        }
        return new StatementResponse(accountNumber, from, to, opening, debits, credits,
                opening.add(credits).subtract(debits), lines);
    }

    public String toCsv(StatementResponse s) {
        StringBuilder sb = new StringBuilder("Date,Reference,Type,Counterparty,Narration,Debit,Credit,Balance\n");
        for (StatementLine l : s.lines()) {
            sb.append(l.date()).append(',').append(l.reference()).append(',').append(l.type()).append(',')
              .append(csv(l.counterparty())).append(',').append(csv(l.narration())).append(',')
              .append(l.debit() == null ? "" : l.debit()).append(',')
              .append(l.credit() == null ? "" : l.credit()).append(',')
              .append(l.balance()).append('\n');
        }
        sb.append("Opening balance,").append(s.openingBalance()).append('\n');
        sb.append("Closing balance,").append(s.closingBalance()).append('\n');
        return sb.toString();
    }

    /** RFC-4180 quoting plus neutralisation of spreadsheet formula injection (=, +, -, @). */
    static String csv(String v) {
        if (v == null) return "";
        String s = v;
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
