package com.securebank.ledger;

import com.securebank.account.Account;
import com.securebank.account.AccountRepository;
import com.securebank.account.AccountStatus;
import com.securebank.common.BusinessException;
import com.securebank.common.ErrorCode;
import com.securebank.config.BankProperties;
import com.securebank.transaction.BankTransaction;
import com.securebank.transaction.TransactionRepository;
import com.securebank.transaction.TransactionStatus;
import com.securebank.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ONLY component that changes balances. Every posting = 1 transaction header + exactly one DEBIT
 * and one CREDIT ledger entry of the same amount (double-entry), written atomically with the balance update.
 * All methods require an enclosing transaction (MANDATORY) so callers control isolation.
 */
@Service
@RequiredArgsConstructor
public class PostingService {

    public record LockedPair(Account debit, Account credit) {}

    public record PostingCommand(TransactionType type, BigDecimal amount, String remarks, String idempotencyKey,
                                 Long initiatedBy, String ipAddress, String country) {}

    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final TransactionRepository transactions;
    private final BankProperties bank;
    private final Clock clock;

    /**
     * Takes row locks (SELECT ... FOR UPDATE) in a fixed, global order (lexicographic by account number).
     * Two concurrent opposite transfers A->B and B->A therefore never wait on each other in a cycle.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedPair lock(String debitNumber, String creditNumber) {
        if (debitNumber.equals(creditNumber)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Source and destination must differ");
        }
        boolean debitFirst = debitNumber.compareTo(creditNumber) < 0;
        Account first = load(debitFirst ? debitNumber : creditNumber);
        Account second = load(debitFirst ? creditNumber : debitNumber);
        return debitFirst ? new LockedPair(first, second) : new LockedPair(second, first);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public BankTransaction postDeposit(String targetAccount, BigDecimal amount, String remarks, Long initiatedBy) {
        LockedPair pair = lock(bank.glAccountNumber(), targetAccount);
        Account target = pair.credit();
        if (target.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_INACTIVE, "Account is not active");
        }
        if (target.isSystemAccount()) {
            throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED, "Cannot deposit to a system account");
        }
        return post(pair, new PostingCommand(TransactionType.DEPOSIT, amount, remarks, null, initiatedBy, null, null));
    }

    /** Both accounts in {@code pair} must already be locked via {@link #lock}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public BankTransaction post(LockedPair pair, PostingCommand cmd) {
        Account debit = pair.debit();
        Account credit = pair.credit();
        BigDecimal amount = cmd.amount();
        if (amount.signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Amount must be positive");
        }
        // Final guard against double-spend, evaluated on the locked, current balance.
        if (!debit.isSystemAccount() && debit.getBalance().add(debit.getOverdraftLimit()).compareTo(amount) < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_FUNDS, "Insufficient funds");
        }
        debit.setBalance(debit.getBalance().subtract(amount));
        credit.setBalance(credit.getBalance().add(amount));
        accounts.save(debit);
        accounts.save(credit);

        BankTransaction tx = transactions.saveAndFlush(BankTransaction.builder()
                .reference("TXN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase())
                .type(cmd.type())
                .status(TransactionStatus.COMPLETED)
                .fromAccount(debit.getAccountNumber())
                .toAccount(credit.getAccountNumber())
                .amount(amount)
                .currency("INR")
                .remarks(cmd.remarks())
                .idempotencyKey(cmd.idempotencyKey())
                .initiatedBy(cmd.initiatedBy())
                .ipAddress(cmd.ipAddress())
                .country(cmd.country())
                .createdAt(clock.instant())
                .build());

        LocalDate today = LocalDate.now(clock);
        ledger.saveAll(List.of(
                entry(tx, debit, EntryType.DEBIT, credit.getAccountNumber(), today),
                entry(tx, credit, EntryType.CREDIT, debit.getAccountNumber(), today)));
        return tx;
    }

    private LedgerEntry entry(BankTransaction tx, Account acct, EntryType type, String counterparty, LocalDate date) {
        return LedgerEntry.builder()
                .transactionId(tx.getId())
                .transactionReference(tx.getReference())
                .accountNumber(acct.getAccountNumber())
                .entryType(type)
                .amount(tx.getAmount())
                .balanceAfter(acct.getBalance())
                .counterpartyAccount(counterparty)
                .narration(tx.getRemarks())
                .entryDate(date)
                .createdAt(clock.instant())
                .build();
    }

    private Account load(String number) {
        return accounts.findForUpdate(number)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Account not found: " + number));
    }
}
