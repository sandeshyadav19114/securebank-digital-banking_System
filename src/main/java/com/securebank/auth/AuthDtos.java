package com.securebank.auth;

import com.securebank.customer.CustomerDtos.CustomerResponse;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Email @Size(max = 160) String email,
            @NotBlank @Pattern(regexp = "^[0-9]{10,15}$", message = "Phone must be 10-15 digits") String phone,
            @NotBlank @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{10,64}$",
                    message = "Password must be 10-64 chars with upper, lower, digit and special character")
            String password) {}

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

    public record VerifyOtpRequest(@NotBlank @Email String email,
                                   @NotBlank @Pattern(regexp = "^[0-9]{4,8}$") String otp) {}

    public record AuthResponse(boolean mfaRequired, String accessToken, String tokenType,
                               Long expiresInSeconds, String message) {
        public static AuthResponse mfa(String message) {
            return new AuthResponse(true, null, null, null, message);
        }

        public static AuthResponse token(String token, long expiresIn) {
            return new AuthResponse(false, token, "Bearer", expiresIn, "Authenticated");
        }
    }

    public record RegisterResponse(CustomerResponse customer) {}
}
