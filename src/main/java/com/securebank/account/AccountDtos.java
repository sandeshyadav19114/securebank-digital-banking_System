package com.securebank.account;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public final class AccountDtos {
    private AccountDtos() {}

    public record OpenAccountRequest(
            @NotNull AccountType accountType,
            @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal initialDeposit,
            @Min(6) @Max(120) Integer tenureMonths) {}

    public record DepositRequest(
            @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @Size(max = 140) String remarks) {}

    public record AccountResponse(String accountNumber, AccountType accountType, BigDecimal balance,
                                  BigDecimal availableBalance, AccountStatus status, BigDecimal interestRate,
                                  LocalDate maturityDate, Instant openedAt) {
        public static AccountResponse from(Account a) {
            return new AccountResponse(a.getAccountNumber(), a.getAccountType(), a.getBalance(),
                    a.getAvailableBalance(), a.getStatus(), a.getInterestRate(), a.getMaturityDate(), a.getOpenedAt());
        }
    }

    public record BalanceResponse(String accountNumber, String currency, BigDecimal balance,
                                  BigDecimal availableBalance, Instant asOf) {}
}
