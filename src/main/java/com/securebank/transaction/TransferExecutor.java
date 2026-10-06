package com.securebank.transaction;

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
import com.securebank.ledger.PostingService.PostingCommand;
import com.securebank.transaction.TransferDtos.TransferRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One ACID unit of work for a fund transfer.
 *  - SERIALIZABLE isolation
 *  - SELECT ... FOR UPDATE on both accounts in deterministic order (see PostingService.lock)
 *  - all validation runs AFTER the locks are held, against the freshest committed balances
 *  - balances, transaction header and both ledger legs commit (or roll back) together
 * Deadlock/lock-timeout failures surface as ConcurrencyFailureException and are retried by TransferService.
 */
@Service
@RequiredArgsConstructor
public class TransferExecutor {

    private final PostingService posting;
    private final BeneficiaryRepository beneficiaries;
    private final BankProperties bank;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional(isolation = Isolation.SERIALIZABLE, timeout = 15)
    public BankTransaction execute(Long customerId, TransferRequest request, String scopedIdempotencyKey,
                                   ClientContext client) {
        BigDecimal amount = request.amount().setScale(2);
        if (request.fromAccount().equals(request.toAccount())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Source and destination must differ");
        }
        if (amount.compareTo(bank.perTransactionLimit()) > 0) {
            throw new BusinessException(ErrorCode.LIMIT_EXCEEDED,
                    "Per-transaction limit is " + bank.perTransactionLimit());
        }

        LockedPair pair = posting.lock(request.fromAccount(), request.toAccount());
        Account source = pair.debit();
        Account destination = pair.credit();

        if (source.isSystemAccount() || !customerId.equals(source.getCustomerId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Source account not found");
        }
        if (source.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_INACTIVE, "Source account is not active");
        }
        if (destination.isSystemAccount() || destination.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_INACTIVE, "Destination account is not active");
        }
        if (source.getAccountType() == AccountType.FIXED_DEPOSIT
                && source.getMaturityDate() != null && source.getMaturityDate().isAfter(LocalDate.now(clock))) {
            throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED, "Fixed deposit has not matured yet");
        }
        if (destination.getAccountType() == AccountType.FIXED_DEPOSIT) {
            throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED, "Cannot transfer into a fixed deposit");
        }
        boolean ownAccount = customerId.equals(destination.getCustomerId());
        if (!ownAccount && !beneficiaries.existsByCustomerIdAndAccountNumberAndActiveTrue(customerId, destination.getAccountNumber())) {
            throw new BusinessException(ErrorCode.BENEFICIARY_REQUIRED, "Destination must be a registered beneficiary");
        }

        BankTransaction tx = posting.post(pair, new PostingCommand(TransactionType.TRANSFER, amount,
                request.remarks(), scopedIdempotencyKey, customerId, client.ip(), client.country()));

        // Delivered to Kafka only AFTER this transaction commits (see TransactionEventPublisher).
        events.publishEvent(new TransactionEvent(tx.getReference(), tx.getFromAccount(), tx.getToAccount(),
                tx.getAmount(), customerId, client.country(), client.ip(), tx.getCreatedAt()));
        return tx;
    }
}
