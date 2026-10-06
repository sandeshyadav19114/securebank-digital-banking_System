package com.securebank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.securebank.account.AccountDtos.AccountResponse;
import com.securebank.account.AccountDtos.DepositRequest;
import com.securebank.account.AccountDtos.OpenAccountRequest;
import com.securebank.audit.AuditService;
import com.securebank.common.BusinessException;
import com.securebank.common.ErrorCode;
import com.securebank.config.BankProperties;
import com.securebank.customer.Customer;
import com.securebank.customer.CustomerRepository;
import com.securebank.customer.KycStatus;
import com.securebank.ledger.PostingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountServiceTest {

    AccountRepository accounts = mock(AccountRepository.class);
    CustomerRepository customers = mock(CustomerRepository.class);
    PostingService posting = mock(PostingService.class);
    AuditService audit = mock(AuditService.class);
    Clock clock = Clock.fixed(Instant.parse("2026-01-15T00:00:00Z"), ZoneOffset.UTC);
    BankProperties bank = new BankProperties(new BigDecimal("1000000"), new BigDecimal("10000"), "GL0000000001");
    AccountService service;
    Customer customer;

    @BeforeEach
    void setUp() {
        service = new AccountService(accounts, customers, posting, bank, audit, clock);
        customer = new Customer();
        customer.setKycStatus(KycStatus.VERIFIED);
        when(customers.findById(1L)).thenReturn(Optional.of(customer));
        when(accounts.existsByAccountNumber(anyString())).thenReturn(false);
        when(accounts.saveAndFlush(any(Account.class))).thenAnswer(i -> i.getArgument(0));
    }

    private void assertCode(Runnable r, ErrorCode code) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(code));
    }

    @Test
    void openRequiresVerifiedKyc() {
        customer.setKycStatus(KycStatus.PENDING);
        assertCode(() -> service.open(1L, new OpenAccountRequest(AccountType.SAVINGS, new BigDecimal("1000"), null)),
                ErrorCode.KYC_NOT_VERIFIED);
        verify(posting, never()).postDeposit(anyString(), any(), anyString(), anyLong());
    }

    @Test
    void unknownCustomerNotFound() {
        assertCode(() -> service.open(77L, new OpenAccountRequest(AccountType.SAVINGS, new BigDecimal("1000"), null)),
                ErrorCode.NOT_FOUND);
    }

    @Test
    void savingsOpensWithGeneratedNumberAndOpeningDeposit() {
        AccountResponse r = service.open(1L, new OpenAccountRequest(AccountType.SAVINGS, new BigDecimal("1000"), null));
        assertThat(r.accountNumber()).matches("SB\\d{12}");
        assertThat(r.interestRate()).isEqualByComparingTo("3.50");
        verify(posting).postDeposit(eq(r.accountNumber()), eq(new BigDecimal("1000.00")), eq("Opening deposit"), eq(1L));
        verify(audit).record(anyString(), eq("ACCOUNT_OPENED"), anyString(), anyString(), eq("SUCCESS"), anyString());
    }

    @Test
    void minimumOpeningDepositsEnforced() {
        assertCode(() -> service.open(1L, new OpenAccountRequest(AccountType.SAVINGS, new BigDecimal("499.99"), null)), ErrorCode.VALIDATION_ERROR);
        assertCode(() -> service.open(1L, new OpenAccountRequest(AccountType.CURRENT, new BigDecimal("4999"), null)), ErrorCode.VALIDATION_ERROR);
        assertCode(() -> service.open(1L, new OpenAccountRequest(AccountType.FIXED_DEPOSIT, new BigDecimal("9999"), 12)), ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void currentAccountGetsOverdraft() {
        AccountResponse r = service.open(1L, new OpenAccountRequest(AccountType.CURRENT, new BigDecimal("5000"), null));
        assertThat(r.availableBalance()).isNotNull();
        assertThat(r.accountType()).isEqualTo(AccountType.CURRENT);
    }

    @Test
    void fixedDepositNeedsTenureAndGetsMaturityAndRate() {
        assertCode(() -> service.open(1L, new OpenAccountRequest(AccountType.FIXED_DEPOSIT, new BigDecimal("10000"), null)), ErrorCode.VALIDATION_ERROR);
        AccountResponse r = service.open(1L, new OpenAccountRequest(AccountType.FIXED_DEPOSIT, new BigDecimal("10000"), 24));
        assertThat(r.maturityDate()).isEqualTo(LocalDate.of(2028, 1, 15));
        assertThat(r.interestRate()).isEqualByComparingTo("7.00");
    }

    @Test
    void fdRateSlabs() {
        assertThat(AccountService.fdRate(6)).isEqualByComparingTo("6.50");
        assertThat(AccountService.fdRate(12)).isEqualByComparingTo("6.50");
        assertThat(AccountService.fdRate(13)).isEqualByComparingTo("7.00");
        assertThat(AccountService.fdRate(36)).isEqualByComparingTo("7.00");
        assertThat(AccountService.fdRate(60)).isEqualByComparingTo("7.25");
    }

    private Account owned(String number, Long owner, String balance) {
        Account a = new Account();
        a.setAccountNumber(number);
        a.setCustomerId(owner);
        a.setAccountType(AccountType.SAVINGS);
        a.setBalance(new BigDecimal(balance));
        return a;
    }

    @Test
    void getOwnedHidesForeignAccounts() {
        when(accounts.findByAccountNumber("SB1")).thenReturn(Optional.of(owned("SB1", 2L, "0")));
        assertCode(() -> service.getOwned("SB1", 1L), ErrorCode.NOT_FOUND);
        assertCode(() -> service.getOwned("NOPE", 1L), ErrorCode.NOT_FOUND);
    }

    @Test
    void listGetAndBalance() {
        Account a = owned("SB1", 1L, "42.00");
        when(accounts.findByCustomerIdOrderByIdAsc(1L)).thenReturn(List.of(a));
        when(accounts.findByAccountNumber("SB1")).thenReturn(Optional.of(a));
        assertThat(service.list(1L)).hasSize(1);
        assertThat(service.get("SB1", 1L).balance()).isEqualByComparingTo("42.00");
        assertThat(service.balance("SB1", 1L).currency()).isEqualTo("INR");
    }

    @Test
    void closeRequiresZeroBalanceAndActive() {
        Account a = owned("SB1", 1L, "5.00");
        when(accounts.findForUpdate("SB1")).thenReturn(Optional.of(a));
        assertCode(() -> service.close("SB1", 1L), ErrorCode.ACCOUNT_RESTRICTED);

        a.setBalance(BigDecimal.ZERO);
        assertThat(service.close("SB1", 1L).status()).isEqualTo(AccountStatus.CLOSED);
        assertCode(() -> service.close("SB1", 1L), ErrorCode.ACCOUNT_INACTIVE);
        assertCode(() -> service.close("SB1", 2L), ErrorCode.NOT_FOUND);
    }

    @Test
    void depositToFixedDepositRejectedOtherwisePosts() {
        Account fd = owned("FD1", 1L, "0");
        fd.setAccountType(AccountType.FIXED_DEPOSIT);
        Account sav = owned("SB1", 1L, "0");
        when(accounts.findByAccountNumber("FD1")).thenReturn(Optional.of(fd));
        when(accounts.findByAccountNumber("SB1")).thenReturn(Optional.of(sav));
        assertCode(() -> service.deposit("FD1", new DepositRequest(BigDecimal.TEN, null), 9L), ErrorCode.ACCOUNT_RESTRICTED);
        assertCode(() -> service.deposit("MISSING", new DepositRequest(BigDecimal.TEN, null), 9L), ErrorCode.NOT_FOUND);

        when(posting.postDeposit(anyString(), any(), anyString(), anyLong())).thenReturn(
                com.securebank.transaction.BankTransaction.builder().reference("TXN-D")
                        .type(com.securebank.transaction.TransactionType.DEPOSIT)
                        .status(com.securebank.transaction.TransactionStatus.COMPLETED)
                        .fromAccount("GL0000000001").toAccount("SB1").amount(BigDecimal.TEN).currency("INR")
                        .createdAt(Instant.now()).build());
        assertThat(service.deposit("SB1", new DepositRequest(BigDecimal.TEN, null), 9L).reference()).isEqualTo("TXN-D");
        verify(posting).postDeposit("SB1", new BigDecimal("10.00"), "Cash deposit", 9L);
    }
}
