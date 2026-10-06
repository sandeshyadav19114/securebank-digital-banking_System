package com.securebank.transaction;

import com.securebank.common.ClientContext;
import com.securebank.security.SecurityUtils;
import com.securebank.transaction.TransferDtos.TransactionResponse;
import com.securebank.transaction.TransferDtos.TransferRequest;
import com.securebank.transaction.TransferDtos.TransferResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
@Validated
@Tag(name = "Transfers")
@SecurityRequirement(name = "bearerAuth")
public class TransferController {

    private final TransferService transferService;
    private final TransactionQueryService queryService;

    @Operation(summary = "Transfer funds",
            description = "Requires an Idempotency-Key header. Retrying with the same key never debits twice; "
                    + "a replay returns 200 with header Idempotent-Replay: true. Optional X-Client-Country (ISO-2) feeds fraud rules.")
    @PostMapping
    public ResponseEntity<TransactionResponse> transfer(
            @Valid @RequestBody TransferRequest request,
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "^[A-Za-z0-9_-]{8,64}$", message = "Idempotency-Key must be 8-64 chars [A-Za-z0-9_-]")
            String idempotencyKey,
            @RequestHeader(value = "X-Client-Country", required = false) String country,
            HttpServletRequest http) {
        TransferResult result = transferService.transfer(SecurityUtils.currentCustomerId(), request,
                idempotencyKey, ClientContext.from(http, country));
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replay", "true").body(result.transaction());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.transaction());
    }

    @GetMapping("/{reference}")
    public TransactionResponse get(@PathVariable String reference) {
        return queryService.getByReference(reference, SecurityUtils.currentCustomerId());
    }
}
