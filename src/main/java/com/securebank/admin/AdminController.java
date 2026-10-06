package com.securebank.admin;

import com.securebank.account.AccountDtos.DepositRequest;
import com.securebank.account.AccountService;
import com.securebank.customer.CustomerDtos.CustomerResponse;
import com.securebank.customer.CustomerDtos.RejectRequest;
import com.securebank.customer.CustomerService;
import com.securebank.fraud.AlertStatus;
import com.securebank.fraud.FraudDetectionService;
import com.securebank.fraud.FraudDtos.AlertResponse;
import com.securebank.ledger.ReconciliationReport;
import com.securebank.ledger.ReconciliationService;
import com.securebank.security.SecurityUtils;
import com.securebank.transaction.TransferDtos.TransactionResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin / Operations")
@SecurityRequirement(name = "bearerAuth")
public class AdminController {

    private final CustomerService customerService;
    private final AccountService accountService;
    private final FraudDetectionService fraudService;
    private final ReconciliationService reconciliationService;
    private final Clock clock;

    @GetMapping("/kyc/pending")
    public Page<CustomerResponse> pendingKyc(@RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return customerService.pendingKyc(PageRequest.of(page, Math.min(size, 100)));
    }

    @PostMapping("/kyc/{customerId}/approve")
    public CustomerResponse approve(@PathVariable Long customerId) {
        return customerService.reviewKyc(customerId, true, null, SecurityUtils.currentCustomerId());
    }

    @PostMapping("/kyc/{customerId}/reject")
    public CustomerResponse reject(@PathVariable Long customerId, @Valid @RequestBody RejectRequest request) {
        return customerService.reviewKyc(customerId, false, request.reason(), SecurityUtils.currentCustomerId());
    }

    @PostMapping("/customers/{customerId}/unlock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlock(@PathVariable Long customerId) {
        customerService.unlock(customerId, SecurityUtils.currentCustomerId());
    }

    @PostMapping("/accounts/{accountNumber}/deposit")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse deposit(@PathVariable String accountNumber, @Valid @RequestBody DepositRequest request) {
        return accountService.deposit(accountNumber, request, SecurityUtils.currentCustomerId());
    }

    @GetMapping("/fraud-alerts")
    public Page<AlertResponse> alerts(@RequestParam(required = false) AlertStatus status,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return fraudService.list(status, PageRequest.of(page, Math.min(size, 100)));
    }

    @PostMapping("/fraud-alerts/{id}/review")
    public AlertResponse reviewAlert(@PathVariable Long id) {
        return fraudService.markReviewed(id, SecurityUtils.currentCustomerId());
    }

    @PostMapping("/reconciliation/run")
    public ReconciliationReport reconcile(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reconciliationService.run(date != null ? date : LocalDate.now(clock));
    }

    @GetMapping("/reconciliation/reports")
    public Page<ReconciliationReport> reports(@RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return reconciliationService.reports(PageRequest.of(page, Math.min(size, 100)));
    }
}
