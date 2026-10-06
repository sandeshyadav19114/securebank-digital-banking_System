package com.securebank.account;

import com.securebank.account.AccountDtos.*;
import com.securebank.audit.AuditService;
import com.securebank.common.BusinessException;
import com.securebank.common.ErrorCode;
import com.securebank.config.BankProperties;
import com.securebank.customer.Customer;
import com.securebank.customer.CustomerRepository;
import com.securebank.customer.KycStatus;
import com.securebank.ledger.PostingService;
import com.securebank.transaction.BankTransaction;
import com.securebank.transaction.TransferDtos.TransactionResponse;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountService {

    private static final BigDecimal MIN_SAVINGS = new BigDecimal("500.00");
    private static final BigDecimal MIN_CURRENT = new BigDecimal("5000.00");
    private static final BigDecimal MIN_FD = new BigDecimal("10000.00");
    private static final BigDecimal SAVINGS_RATE = new BigDecimal("3.50");

    private final AccountRepository accounts;
    private final CustomerRepository customers;
    private final PostingService posting;
    private final BankProperties bank;
    private final AuditService audit;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public AccountResponse open(Long customerId, OpenAccountRequest r) {
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Customer not found"));
        if (customer.getKycStatus() != KycStatus.VERIFIED) {
            throw new BusinessException(ErrorCode.KYC_NOT_VERIFIED, "KYC must be verified before opening an account");
        }
        BigDecimal deposit = r.initialDeposit().setScale(2);
        Account a = new Account();
        a.setCustomerId(customerId);
        a.setAccountType(r.accountType());
        switch (r.accountType()) {
            case SAVINGS -> {
                requireMin(deposit, MIN_SAVINGS, "savings");
                a.setInterestRate(SAVINGS_RATE);
            }
            case CURRENT -> {
                requireMin(deposit, MIN_CURRENT, "current");
                a.setOverdraftLimit(bank.currentOverdraftLimit());
                a.setInterestRate(BigDecimal.ZERO.setScale(2));
            }
            case FIXED_DEPOSIT -> {
                if (r.tenureMonths() == null) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR, "tenureMonths is required for fixed deposits");
                }
                requireMin(deposit, MIN_FD, "fixed deposit");
                a.setInterestRate(fdRate(r.tenureMonths()));
                a.setMaturityDate(LocalDate.now(clock).plusMonths(r.tenureMonths()));
            }
        }
        a.setAccountNumber(newAccountNumber());
        accounts.saveAndFlush(a);
        posting.postDeposit(a.getAccountNumber(), deposit, "Opening deposit", customerId);
        audit.record("customer:" + customerId, "ACCOUNT_OPENED", "Account", a.getAccountNumber(), "SUCCESS",
                r.accountType() + " deposit=" + deposit);
        return AccountResponse.from(a);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list(Long customerId) {
        return accounts.findByCustomerIdOrderByIdAsc(customerId).stream().map(AccountResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse get(String accountNumber, Long customerId) {
        return AccountResponse.from(getOwned(accountNumber, customerId));
    }

    @Transactional(readOnly = true)
    public BalanceResponse balance(String accountNumber, Long customerId) {
        Account a = getOwned(accountNumber, customerId);
        return new BalanceResponse(a.getAccountNumber(), "INR", a.getBalance(), a.getAvailableBalance(), clock.instant());
    }

    /** Ownership-checked lookup. Unknown and foreign accounts look identical to the caller (no enumeration). */
    public Account getOwned(String accountNumber, Long customerId) {
        return accounts.findByAccountNumber(accountNumber)
                .filter(a -> customerId.equals(a.getCustomerId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Account not found"));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public AccountResponse close(String accountNumber, Long customerId) {
        Account a = accounts.findForUpdate(accountNumber)
                .filter(x -> customerId.equals(x.getCustomerId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Account not found"));
        if (a.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_INACTIVE, "Account is not active");
        }
        if (a.getBalance().signum() != 0) {
            throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED, "Balance must be zero before closing the account");
        }
        a.setStatus(AccountStatus.CLOSED);
        a.setClosedAt(Instant.now(clock));
        accounts.save(a);
        audit.record("customer:" + customerId, "ACCOUNT_CLOSED", "Account", accountNumber, "SUCCESS", null);
        return AccountResponse.from(a);
    }

    /** Teller/admin cash deposit: DR bank cash GL, CR customer account. */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TransactionResponse deposit(String accountNumber, DepositRequest r, Long adminId) {
        Account target = accounts.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Account not found"));
        if (target.getAccountType() == AccountType.FIXED_DEPOSIT) {
            throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED, "Deposits to a fixed deposit are not allowed");
        }
        BankTransaction tx = posting.postDeposit(accountNumber, r.amount().setScale(2),
                r.remarks() == null ? "Cash deposit" : r.remarks(), adminId);
        audit.record("admin:" + adminId, "DEPOSIT", "Account", accountNumber, "SUCCESS", "amount=" + r.amount());
        return TransactionResponse.from(tx);
    }

    static BigDecimal fdRate(int tenureMonths) {
        if (tenureMonths <= 12) return new BigDecimal("6.50");
        if (tenureMonths <= 36) return new BigDecimal("7.00");
        return new BigDecimal("7.25");
    }

    private static void requireMin(BigDecimal deposit, BigDecimal min, String type) {
        if (deposit.compareTo(min) < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Minimum opening deposit for a " + type + " account is " + min);
        }
    }

    private String newAccountNumber() {
        String number;
        do {
            number = "SB" + String.format("%012d", Math.floorMod(random.nextLong(), 1_000_000_000_000L));
        } while (accounts.existsByAccountNumber(number));
        return number;
    }
}
