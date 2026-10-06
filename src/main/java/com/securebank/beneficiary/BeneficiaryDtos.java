package com.securebank.beneficiary;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class BeneficiaryDtos {
    private BeneficiaryDtos() {}

    public record AddBeneficiaryRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9]{8,20}$", message = "Invalid account number") String accountNumber,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 60) String nickname) {}

    public record BeneficiaryResponse(Long id, String accountNumber, String name, String nickname,
                                      String bankName, Instant createdAt) {
        public static BeneficiaryResponse from(Beneficiary b) {
            return new BeneficiaryResponse(b.getId(), b.getAccountNumber(), b.getName(), b.getNickname(),
                    b.getBankName(), b.getCreatedAt());
        }
    }
}
