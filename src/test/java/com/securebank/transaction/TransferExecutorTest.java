package com.securebank.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.securebank.account.Account;
import com.securebank.account.AccountStatus;
import com.securebank.account.AccountType;
import com.securebank.beneficiary.BeneficiaryRepository;
import com.securebank.common.BusinessException;
import com.securebank.common.ClientContext;
import com.securebank.common.ErrorCode;
import com.securebank.config.BankProperties;
import com.securebank.fraud.TransactionEvent;
import com.securebank.ledger.PostingService;
import com.securebank.ledger.PostingService.LockedPair;
import com.securebank.transaction.TransferDtos.TransferRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class TransferExecutorTest {

    static final Long ME = 1L;
    static final Long OTHER = 2L;

    PostingService posting = mock(PostingService.class);
    BeneficiaryRepository beneficiaries = mock(BeneficiaryRepository.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
    BankProperties bank = new BankProperties(new BigDecimal("1000000"), new BigDecimal("10000"), "GL0000000001");
    TransferExecutor executor;
    ClientContext ctx = new ClientContext("10.0.0.1", "IN");

    @BeforeEach
    void setUp() {
        executor = new TransferExecutor(posting, beneficiaries, bank, events, clock);
    }

    private Account acct(String number, Long owner, AccountType type) {
        Account a = new Account();
        a.setAccountNumber(number);
        a.setCustomerId(owner);
        a.setAccountType(type);
        a.setBalance(new BigDecimal("1000.00"));
        return a;
    }

    private TransferRequest req(String amount) {
        return new TransferRequest("SRC00000001", "DST00000001", new BigDecimal(amount), "rent");
    }

    private void lockReturns(Account src, Account dst) {
        when(posting.lock("SRC00000001", "DST00000001")).thenReturn(new LockedPair(src, dst));
        when(posting.post(any(), any())).thenAnswer(i -> BankTransaction.builder()
                .reference("TXN-1").type(TransactionType.TRANSFER).status(TransactionStatus.COMPLETED)
                .fromAccount("SRC00000001").toAccount("DST00000001").amount(new BigDecimal("100.00"))
                .currency("INR").createdAt(clock.instant()).build());
    }

    private void assertCode(Runnable r, ErrorCode code) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(code));
    }

    @Test
    void rejectsSameAccountBeforeLocking() {
        TransferRequest same = new TransferRequest("SRC00000001", "SRC00000001", BigDecimal.TEN, null);
        assertCode(() -> executor.execute(ME, same, "k", ctx), ErrorCode.VALIDATION_ERROR);
        verify(posting, never()).lock(anyString(), anyString());
    }

    @Test
    void rejectsAmountAbovePerTransactionLimit() {
        assertCode(() -> executor.execute(ME, req("1000000.01"), "k", ctx), ErrorCode.LIMIT_EXCEEDED);
    }

    @Test
    void foreignSourceAccountLooksLikeNotFound() {
        lockReturns(acct("SRC00000001", OTHER, AccountType.SAVINGS), acct("DST00000001", ME, AccountType.SAVINGS));
        assertCode(() -> executor.execute(ME, req("100"), "k", ctx), ErrorCode.NOT_FOUND);
        verify(posting, never()).post(any(), any());
    }

    @Test
    void frozenSourceRejected() {
        Account src = acct("SRC00000001", ME, AccountType.SAVINGS);
        src.setStatus(AccountStatus.FROZEN);
        lockReturns(src, acct("DST00000001", ME, AccountType.SAVINGS));
        assertCode(() -> executor.execute(ME, req("100"), "k", ctx), ErrorCode.ACCOUNT_INACTIVE);
    }

    @Test
    void closedDestinationRejected() {
        Account dst = acct("DST00000001", ME, AccountType.SAVINGS);
        dst.setStatus(AccountStatus.CLOSED);
        lockReturns(acct("SRC00000001", ME, AccountType.SAVINGS), dst);
        assertCode(() -> executor.execute(ME, req("100"), "k", ctx), ErrorCode.ACCOUNT_INACTIVE);
    }

    @Test
    void unmaturedFixedDepositCannotBeDebited() {
        Account fd = acct("SRC00000001", ME, AccountType.FIXED_DEPOSIT);
        fd.setMaturityDate(LocalDate.of(2026, 12, 1));
        lockReturns(fd, acct("DST00000001", ME, AccountType.SAVINGS));
        assertCode(() -> executor.execute(ME, req("100"), "k", ctx), ErrorCode.ACCOUNT_RESTRICTED);
    }

    @Test
    void maturedFixedDepositCanBeDebited() {
        Account fd = acct("SRC00000001", ME, AccountType.FIXED_DEPOSIT);
        fd.setMaturityDate(LocalDate.of(2026, 5, 1));
        lockReturns(fd, acct("DST00000001", ME, AccountType.SAVINGS));
        assertThat(executor.execute(ME, req("100"), "k", ctx)).isNotNull();
    }

    @Test
    void cannotTransferIntoFixedDeposit() {
        lockReturns(acct("SRC00000001", ME, AccountType.SAVINGS), acct("DST00000001", ME, AccountType.FIXED_DEPOSIT));
        assertCode(() -> executor.execute(ME, req("100"), "k", ctx), ErrorCode.ACCOUNT_RESTRICTED);
    }

    @Test
    void thirdPartyDestinationRequiresBeneficiary() {
        lockReturns(acct("SRC00000001", ME, AccountType.SAVINGS), acct("DST00000001", OTHER, AccountType.SAVINGS));
        when(beneficiaries.existsByCustomerIdAndAccountNumberAndActiveTrue(ME, "DST00000001")).thenReturn(false);
        assertCode(() -> executor.execute(ME, req("100"), "k", ctx), ErrorCode.BENEFICIARY_REQUIRED);
    }

    @Test
    void ownAccountTransferNeedsNoBeneficiaryAndPublishesEvent() {
        lockReturns(acct("SRC00000001", ME, AccountType.SAVINGS), acct("DST00000001", ME, AccountType.CURRENT));
        BankTransaction tx = executor.execute(ME, req("100"), "1:key", ctx);
        assertThat(tx.getReference()).isEqualTo("TXN-1");
        verify(beneficiaries, never()).existsByCustomerIdAndAccountNumberAndActiveTrue(anyLong(), anyString());

        ArgumentCaptor<TransactionEvent> ev = ArgumentCaptor.forClass(TransactionEvent.class);
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue().country()).isEqualTo("IN");
        assertThat(ev.getValue().customerId()).isEqualTo(ME);
    }

    @Test
    void registeredBeneficiaryAllowed() {
        lockReturns(acct("SRC00000001", ME, AccountType.SAVINGS), acct("DST00000001", OTHER, AccountType.SAVINGS));
        when(beneficiaries.existsByCustomerIdAndAccountNumberAndActiveTrue(ME, "DST00000001")).thenReturn(true);
        assertThat(executor.execute(ME, req("100"), "k", ctx)).isNotNull();
    }
}
