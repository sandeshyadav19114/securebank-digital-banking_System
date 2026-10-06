package com.securebank.account;

import com.securebank.account.AccountDtos.*;
import com.securebank.security.SecurityUtils;
import com.securebank.transaction.TransactionQueryService;
import com.securebank.transaction.TransferDtos.TransactionResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Tag(name = "Accounts")
@SecurityRequirement(name = "bearerAuth")
public class AccountController {

    private final AccountService service;
    private final TransactionQueryService transactionQuery;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse open(@Valid @RequestBody OpenAccountRequest request) {
        return service.open(SecurityUtils.currentCustomerId(), request);
    }

    @GetMapping
    public List<AccountResponse> list() {
        return service.list(SecurityUtils.currentCustomerId());
    }

    @GetMapping("/{accountNumber}")
    public AccountResponse get(@PathVariable String accountNumber) {
        return service.get(accountNumber, SecurityUtils.currentCustomerId());
    }

    @GetMapping("/{accountNumber}/balance")
    public BalanceResponse balance(@PathVariable String accountNumber) {
        return service.balance(accountNumber, SecurityUtils.currentCustomerId());
    }

    @PostMapping("/{accountNumber}/close")
    public AccountResponse close(@PathVariable String accountNumber) {
        return service.close(accountNumber, SecurityUtils.currentCustomerId());
    }

    @GetMapping("/{accountNumber}/transactions")
    public Page<TransactionResponse> transactions(@PathVariable String accountNumber,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return transactionQuery.history(accountNumber, SecurityUtils.currentCustomerId(), page, Math.min(size, 100));
    }
}
