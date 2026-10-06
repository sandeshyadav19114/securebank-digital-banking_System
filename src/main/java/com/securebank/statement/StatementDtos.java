package com.securebank.statement;

import com.securebank.ledger.EntryType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class StatementDtos {
    private StatementDtos() {}

    public record StatementLine(LocalDate date, String reference, EntryType type, String counterparty,
                                String narration, BigDecimal debit, BigDecimal credit, BigDecimal balance) {}

    public record StatementResponse(String accountNumber, LocalDate from, LocalDate to, BigDecimal openingBalance,
                                    BigDecimal totalDebits, BigDecimal totalCredits, BigDecimal closingBalance,
                                    List<StatementLine> lines) {}
}
