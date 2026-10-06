package com.securebank.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.securebank.account.AccountService;
import com.securebank.common.BusinessException;
import com.securebank.ledger.EntryType;
import com.securebank.ledger.LedgerEntry;
import com.securebank.ledger.LedgerRepository;
import com.securebank.statement.StatementDtos.StatementResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StatementServiceTest {

    LedgerRepository ledger = mock(LedgerRepository.class);
    AccountService accountService = mock(AccountService.class);
    StatementService service;
    final LocalDate from = LocalDate.of(2026, 1, 1);
    final LocalDate to = LocalDate.of(2026, 1, 31);

    @BeforeEach
    void setUp() {
        service = new StatementService(ledger, accountService);
    }

    private LedgerEntry entry(EntryType type, String amount, String after, String narration) {
        return LedgerEntry.builder().transactionReference("TXN-" + amount).accountNumber("SB1").entryType(type)
                .amount(new BigDecimal(amount)).balanceAfter(new BigDecimal(after)).counterpartyAccount("SB2")
                .narration(narration).entryDate(LocalDate.of(2026, 1, 10)).createdAt(Instant.now()).build();
    }

    @Test
    void computesOpeningTotalsAndClosing() {
        when(ledger.sumBefore("SB1", EntryType.CREDIT, from)).thenReturn(new BigDecimal("1000.00"));
        when(ledger.sumBefore("SB1", EntryType.DEBIT, from)).thenReturn(new BigDecimal("200.00"));
        when(ledger.findByAccountNumberAndEntryDateBetweenOrderByIdAsc("SB1", from, to)).thenReturn(List.of(
                entry(EntryType.DEBIT, "300.00", "500.00", "rent"),
                entry(EntryType.CREDIT, "50.00", "550.00", "refund")));

        StatementResponse s = service.generate("SB1", 1L, from, to);

        assertThat(s.openingBalance()).isEqualByComparingTo("800.00");
        assertThat(s.totalDebits()).isEqualByComparingTo("300.00");
        assertThat(s.totalCredits()).isEqualByComparingTo("50.00");
        assertThat(s.closingBalance()).isEqualByComparingTo("550.00");
        assertThat(s.lines()).hasSize(2);
        assertThat(s.lines().get(0).debit()).isEqualByComparingTo("300.00");
        assertThat(s.lines().get(0).credit()).isNull();
    }

    @Test
    void emptyLedgerGivesZeroStatement() {
        when(ledger.findByAccountNumberAndEntryDateBetweenOrderByIdAsc(any(), any(), any())).thenReturn(List.of());
        StatementResponse s = service.generate("SB1", 1L, from, to);
        assertThat(s.openingBalance()).isEqualByComparingTo("0");
        assertThat(s.closingBalance()).isEqualByComparingTo("0");
    }

    @Test
    void validatesDateRange() {
        assertThatThrownBy(() -> service.generate("SB1", 1L, to, from)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.generate("SB1", 1L, from, from.plusDays(400))).isInstanceOf(BusinessException.class);
    }

    @Test
    void csvIncludesRowsAndNeutralisesFormulaInjection() {
        when(ledger.findByAccountNumberAndEntryDateBetweenOrderByIdAsc("SB1", from, to)).thenReturn(List.of(
                entry(EntryType.DEBIT, "10.00", "90.00", "=HYPERLINK(\"http://evil\")"),
                entry(EntryType.CREDIT, "5.00", "95.00", "a,b")));
        String csv = service.toCsv(service.generate("SB1", 1L, from, to));
        assertThat(csv).startsWith("Date,Reference,Type");
        assertThat(csv).contains("'=HYPERLINK");
        assertThat(csv).contains("\"a,b\"");
        assertThat(csv).contains("Closing balance");
    }

    @Test
    void csvEscapeHelper() {
        assertThat(StatementService.csv(null)).isEmpty();
        assertThat(StatementService.csv("plain")).isEqualTo("plain");
        assertThat(StatementService.csv("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(StatementService.csv("+1")).isEqualTo("'+1");
    }
}
