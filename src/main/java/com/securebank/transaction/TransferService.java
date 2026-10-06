package com.securebank.transaction;

import com.securebank.audit.AuditService;
import com.securebank.common.BusinessException;
import com.securebank.common.ClientContext;
import com.securebank.common.ErrorCode;
import com.securebank.transaction.TransferDtos.TransactionResponse;
import com.securebank.transaction.TransferDtos.TransferRequest;
import com.securebank.transaction.TransferDtos.TransferResult;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Idempotent + retrying facade around {@link TransferExecutor} (deliberately NOT transactional itself).
 *  1. Idempotency: same Idempotency-Key => original result is replayed, money moves once.
 *     A concurrent duplicate that races past the lookup is stopped by the UNIQUE index on idempotency_key.
 *  2. Retries: deadlocks / lock timeouts under SERIALIZABLE are retried with back-off.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    static final int MAX_ATTEMPTS = 3;

    private final TransferExecutor executor;
    private final TransactionRepository transactions;
    private final AuditService audit;

    public TransferResult transfer(Long customerId, TransferRequest request, String idempotencyKey, ClientContext client) {
        String scopedKey = customerId + ":" + idempotencyKey;
        String actor = "customer:" + customerId;

        Optional<BankTransaction> existing = transactions.findByIdempotencyKey(scopedKey);
        if (existing.isPresent()) {
            return replay(existing.get(), request);
        }

        for (int attempt = 1; ; attempt++) {
            try {
                BankTransaction tx = executor.execute(customerId, request, scopedKey, client);
                audit.record(actor, "TRANSFER", "Transaction", tx.getReference(), "SUCCESS",
                        request.fromAccount() + "->" + request.toAccount() + " amount=" + tx.getAmount());
                return new TransferResult(TransactionResponse.from(tx), false);
            } catch (DataIntegrityViolationException e) {
                Optional<BankTransaction> winner = transactions.findByIdempotencyKey(scopedKey);
                if (winner.isPresent()) {
                    return replay(winner.get(), request);
                }
                throw e;
            } catch (ConcurrencyFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    audit.record(actor, "TRANSFER", "Account", request.fromAccount(), "CONFLICT", "lock contention");
                    throw new BusinessException(ErrorCode.CONCURRENCY_CONFLICT, "System busy, please retry the request");
                }
                log.warn("transfer_retry attempt={} reason={}", attempt, e.getClass().getSimpleName());
                backoff(attempt);
            } catch (BusinessException e) {
                audit.record(actor, "TRANSFER", "Account", request.fromAccount(), "REJECTED_" + e.getCode(),
                        "to=" + request.toAccount() + " amount=" + request.amount());
                throw e;
            }
        }
    }

    private TransferResult replay(BankTransaction tx, TransferRequest request) {
        boolean same = tx.getFromAccount().equals(request.fromAccount())
                && tx.getToAccount().equals(request.toAccount())
                && tx.getAmount().compareTo(request.amount()) == 0;
        if (!same) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "Idempotency-Key was already used with a different request payload");
        }
        return new TransferResult(TransactionResponse.from(tx), true);
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(50L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
