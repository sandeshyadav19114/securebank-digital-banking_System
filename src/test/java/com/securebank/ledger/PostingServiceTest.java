package com.securebank.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.securebank.account.Account;
import com.securebank.account.AccountRepository;
import com.securebank.account.AccountStatus;
import com.securebank.account.AccountType;
import com.securebank.common.BusinessException;
import com.securebank.common.ErrorCode;
import com.securebank.config.BankProperties;
import com.securebank.ledger.PostingService.LockedPair;
import com.securebank.ledger.PostingService.PostingCommand;
import com.securebank.transaction.BankTransaction;
import com.securebank.transaction.TransactionRepository;
import com.securebank.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class PostingServiceTest {

    AccountRepository accounts = mock(AccountRepository.class);
    LedgerRepository ledger = mock(LedgerRepository.class);
    TransactionRepository transactions = mock(TransactionRepository.class);
    BankProperties bank = new BankProperties(new BigDecimal("1000000"), new BigDecimal("10000"), "GL0000000001");
    Clock clock = Clock.fixed(Instant.parse("2026-03-10T08:00:00Z"), ZoneOffset.UTC);
    PostingService service;

    @BeforeEach
    void setUp() {
        service = new PostingService(accounts, ledger, transactions, bank, clock);
        when(transactions.saveAndFlush(any(BankTransaction.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Account acct(String number, String balance, String overdraft) {
        Account a = new Account();
        a.setAccountNumber(number);
        a.setAccountType(AccountType.SAVINGS);
        a.setBalance(new BigDecimal(balance));
        a.setOverdraftLimit(new BigDecimal(overdraft));
        return a;
    }

    private PostingCommand cmd(String amount) {
        return new PostingCommand(TransactionType.TRANSFER, new BigDecimal(amount), "memo", "1:k", 1L, "ip", "IN");
    }

    @Test
    void locksInGlobalOrderRegardlessOfDirection() {
        Account a = acct("SB0000000001", "0", "0");
        Account b = acct("SB0000000002", "0", "0");
        when(accounts.findForUpdate("SB0000000001")).thenReturn(Optional.of(a));
        when(accounts.findForUpdate("SB0000000002")).thenReturn(Optional.of(b));

        LockedPair forward = service.lock("SB0000000001", "SB0000000002");
        LockedPair reverse = service.lock("SB0000000002", "SB0000000001");

        assertThat(forward.debit()).isSameAs(a);
        assertThat(reverse.debit()).isSameAs(b);
        InOrder order = inOrder(accounts);
        order.verify(accounts).findForUpdate("SB0000000001");
        order.verify(accounts).findForUpdate("SB0000000002");
        order.verify(accounts).findForUpdate("SB0000000001");
        order.verify(accounts).findForUpdate("SB0000000002");
    }

    @Test
    void lockRejectsSameAccountAndUnknownAccount() {
        assertThatThrownBy(() -> service.lock("X", "X")).isInstanceOf(BusinessException.class);
        when(accounts.findForUpdate(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.lock("A", "B")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @SuppressWarnings("unchecked")
    void postWritesBalancedDoubleEntryAndMovesBalances() {
        Account src = acct("SB0000000001", "1000.00", "0");
        Account dst = acct("SB0000000002", "50.00", "0");

        BankTransaction tx = service.post(new LockedPair(src, dst), cmd("250.00"));

        assertThat(src.getBalance()).isEqualByComparingTo("750.00");
        assertThat(dst.getBalance()).isEqualByComparingTo("300.00");
        assertThat(tx.getReference()).startsWith("TXN-");
        assertThat(tx.getIdempotencyKey()).isEqualTo("1:k");

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(ledger).saveAll(captor.capture());
        List<LedgerEntry> entries = new ArrayList<>(captor.getValue());
        assertThat(entries).hasSize(2);
        LedgerEntry debit = entries.stream().filter(e -> e.getEntryType() == EntryType.DEBIT).findFirst().orElseThrow();
        LedgerEntry credit = entries.stream().filter(e -> e.getEntryType() == EntryType.CREDIT).findFirst().orElseThrow();
        assertThat(debit.getAmount()).isEqualByComparingTo(credit.getAmount());
        assertThat(debit.getAccountNumber()).isEqualTo("SB0000000001");
        assertThat(debit.getBalanceAfter()).isEqualByComparingTo("750.00");
        assertThat(credit.getBalanceAfter()).isEqualByComparingTo("300.00");
        assertThat(debit.getEntryDate()).isEqualTo(LocalDate.of(2026, 3, 10));
    }

    @Test
    void insufficientFundsChangesNothing() {
        Account src = acct("SB0000000001", "100.00", "0");
        Account dst = acct("SB0000000002", "0.00", "0");
        assertThatThrownBy(() -> service.post(new LockedPair(src, dst), cmd("100.01")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS));
        assertThat(src.getBalance()).isEqualByComparingTo("100.00");
        verify(ledger, never()).saveAll(any());
    }

    @Test
    void overdraftLimitExtendsSpendingPower() {
        Account src = acct("SB0000000001", "100.00", "500.00");
        Account dst = acct("SB0000000002", "0.00", "0");
        service.post(new LockedPair(src, dst), cmd("600.00"));
        assertThat(src.getBalance()).isEqualByComparingTo("-500.00");
        assertThatThrownBy(() -> service.post(new LockedPair(src, dst), cmd("0.01"))).isInstanceOf(BusinessException.class);
    }

    @Test
    void systemAccountMayGoNegative() {
        Account gl = acct("GL0000000001", "0.00", "0");
        gl.setSystemAccount(true);
        Account customer = acct("SB0000000001", "0.00", "0");
        service.post(new LockedPair(gl, customer), cmd("5000.00"));
        assertThat(gl.getBalance()).isEqualByComparingTo("-5000.00");
        assertThat(customer.getBalance()).isEqualByComparingTo("5000.00");
    }

    @Test
    void nonPositiveAmountRejected() {
        assertThatThrownBy(() -> service.post(new LockedPair(acct("A", "1", "0"), acct("B", "1", "0")), cmd("0.00")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void depositDebitsGlAndCreditsCustomer() {
        Account gl = acct("GL0000000001", "0.00", "0");
        gl.setSystemAccount(true);
        Account customer = acct("SB0000000001", "10.00", "0");
        when(accounts.findForUpdate("GL0000000001")).thenReturn(Optional.of(gl));
        when(accounts.findForUpdate("SB0000000001")).thenReturn(Optional.of(customer));

        BankTransaction tx = service.postDeposit("SB0000000001", new BigDecimal("90.00"), "cash", 99L);

        assertThat(tx.getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(customer.getBalance()).isEqualByComparingTo("100.00");
        assertThat(gl.getBalance()).isEqualByComparingTo("-90.00");
    }

    @Test
    void depositToInactiveOrSystemAccountRejected() {
        Account gl = acct("GL0000000001", "0.00", "0");
        gl.setSystemAccount(true);
        Account frozen = acct("SB0000000001", "0", "0");
        frozen.setStatus(AccountStatus.FROZEN);
        when(accounts.findForUpdate("GL0000000001")).thenReturn(Optional.of(gl));
        when(accounts.findForUpdate("SB0000000001")).thenReturn(Optional.of(frozen));
        assertThatThrownBy(() -> service.postDeposit("SB0000000001", BigDecimal.TEN, "x", 1L))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ACCOUNT_INACTIVE));

        Account other = acct("SB0000000002", "0", "0");
        other.setSystemAccount(true);
        when(accounts.findForUpdate("SB0000000002")).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.postDeposit("SB0000000002", BigDecimal.TEN, "x", 1L))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ACCOUNT_RESTRICTED));
    }
}
