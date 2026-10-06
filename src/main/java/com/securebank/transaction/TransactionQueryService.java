package com.securebank.transaction;

import com.securebank.account.Account;
import com.securebank.account.AccountRepository;
import com.securebank.account.AccountService;
import com.securebank.common.BusinessException;
import com.securebank.common.ErrorCode;
import com.securebank.transaction.TransferDtos.TransactionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransactionQueryService {

    private final TransactionRepository transactions;
    private final AccountRepository accounts;
    private final AccountService accountService;

    @Transactional(readOnly = true)
    public TransactionResponse getByReference(String reference, Long customerId) {
        BankTransaction tx = transactions.findByReference(reference)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Transaction not found"));
        if (!owns(tx.getFromAccount(), customerId) && !owns(tx.getToAccount(), customerId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Transaction not found");
        }
        return TransactionResponse.from(tx);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> history(String accountNumber, Long customerId, int page, int size) {
        accountService.getOwned(accountNumber, customerId);
        return transactions.findByAccount(accountNumber, PageRequest.of(Math.max(page, 0), Math.max(size, 1)))
                .map(TransactionResponse::from);
    }

    private boolean owns(String accountNumber, Long customerId) {
        return accounts.findByAccountNumber(accountNumber).map(Account::getCustomerId)
                .map(customerId::equals).orElse(false);
    }
}
