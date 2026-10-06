package com.securebank.transaction;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

public final class TransferDtos {
    private TransferDtos() {}

    public record TransferRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9]{8,20}$", message = "Invalid account number") String fromAccount,
            @NotBlank @Pattern(regexp = "^[A-Z0-9]{8,20}$", message = "Invalid account number") String toAccount,
            @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @Size(max = 140) String remarks) {}

    public record TransactionResponse(String reference, TransactionType type, TransactionStatus status,
                                      String fromAccount, String toAccount, BigDecimal amount, String currency,
                                      String remarks, Instant createdAt) {
        public static TransactionResponse from(BankTransaction t) {
            return new TransactionResponse(t.getReference(), t.getType(), t.getStatus(), t.getFromAccount(),
                    t.getToAccount(), t.getAmount(), t.getCurrency(), t.getRemarks(), t.getCreatedAt());
        }
    }

    public record TransferResult(TransactionResponse transaction, boolean replayed) {}
}
