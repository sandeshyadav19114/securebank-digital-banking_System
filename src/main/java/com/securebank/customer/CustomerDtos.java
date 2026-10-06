package com.securebank.customer;

import com.securebank.common.Masking;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class CustomerDtos {
    private CustomerDtos() {}

    public record KycRequest(
            @NotBlank @Pattern(regexp = "^[A-Z]{5}[0-9]{4}[A-Z]$", message = "Invalid PAN format") String panNumber,
            @NotBlank @Pattern(regexp = "^[0-9]{12}$", message = "Aadhaar must be 12 digits") String aadhaarNumber,
            @NotBlank @Size(max = 500) String address) {}

    public record RejectRequest(@NotBlank @Size(max = 250) String reason) {}

    public record CustomerResponse(Long id, String fullName, String email, String phone, Role role,
                                   KycStatus kycStatus, String kycRejectionReason, String panMasked,
                                   String aadhaarMasked, boolean mfaEnabled) {
        public static CustomerResponse from(Customer c) {
            return new CustomerResponse(c.getId(), c.getFullName(), c.getEmail(), c.getPhone(), c.getRole(),
                    c.getKycStatus(), c.getKycRejectionReason(),
                    Masking.mask(c.getPanNumber(), 4), Masking.mask(c.getAadhaarNumber(), 4), c.isMfaEnabled());
        }
    }
}
