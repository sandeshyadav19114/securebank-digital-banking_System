package com.securebank.auth;

import com.securebank.audit.AuditService;
import com.securebank.auth.AuthDtos.*;
import com.securebank.common.BusinessException;
import com.securebank.common.ErrorCode;
import com.securebank.config.LockoutProperties;
import com.securebank.customer.Customer;
import com.securebank.customer.CustomerDtos.CustomerResponse;
import com.securebank.customer.CustomerRepository;
import com.securebank.security.JwtService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class AuthService {

    private final CustomerRepository customers;
    private final PasswordEncoder encoder;
    private final OtpService otpService;
    private final JwtService jwtService;
    private final LockoutProperties lockout;
    private final AuditService audit;
    private final Clock clock;
    /** Used to equalise response time when the e-mail is unknown (prevents user enumeration by timing). */
    private final String dummyHash;

    public AuthService(CustomerRepository customers, PasswordEncoder encoder, OtpService otpService,
                       JwtService jwtService, LockoutProperties lockout, AuditService audit, Clock clock) {
        this.customers = customers;
        this.encoder = encoder;
        this.otpService = otpService;
        this.jwtService = jwtService;
        this.lockout = lockout;
        this.audit = audit;
        this.clock = clock;
        this.dummyHash = encoder.encode("dummy-password-for-timing");
    }

    @Transactional
    public CustomerResponse register(RegisterRequest r) {
        String email = r.email().trim().toLowerCase();
        if (customers.existsByEmailIgnoreCase(email)) {
            throw new BusinessException(ErrorCode.DUPLICATE, "E-mail is already registered");
        }
        Customer c = new Customer();
        c.setFullName(r.fullName().trim());
        c.setEmail(email);
        c.setPhone(r.phone());
        c.setPasswordHash(encoder.encode(r.password()));   // BCrypt, per-hash random salt
        customers.save(c);
        audit.record("anonymous", "CUSTOMER_REGISTERED", "Customer", String.valueOf(c.getId()), "SUCCESS", email);
        return CustomerResponse.from(c);
    }

    /** noRollbackFor: failed-attempt counters must be persisted even though we throw. */
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResponse login(LoginRequest r) {
        String email = r.email().trim().toLowerCase();
        Customer c = customers.findByEmailIgnoreCase(email).orElse(null);
        if (c == null) {
            encoder.matches(r.password(), dummyHash);
            audit.record(email, "LOGIN", "Customer", null, "FAILED", "unknown user");
            throw new BusinessException(ErrorCode.BAD_CREDENTIALS, "Invalid e-mail or password");
        }
        Instant now = clock.instant();
        if (!c.isEnabled()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Account is disabled");
        }
        if (c.getLockedUntil() != null && c.getLockedUntil().isAfter(now)) {
            audit.record(email, "LOGIN", "Customer", String.valueOf(c.getId()), "LOCKED", null);
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED, "Account is temporarily locked. Try again later.");
        }
        if (!encoder.matches(r.password(), c.getPasswordHash())) {
            int attempts = c.getFailedLoginAttempts() + 1;
            boolean nowLocked = attempts >= lockout.maxFailedAttempts();
            if (nowLocked) {
                c.setLockedUntil(now.plus(Duration.ofMinutes(lockout.lockMinutes())));
                c.setFailedLoginAttempts(0);
            } else {
                c.setFailedLoginAttempts(attempts);
            }
            customers.save(c);
            audit.record(email, "LOGIN", "Customer", String.valueOf(c.getId()),
                    nowLocked ? "LOCKED_OUT" : "FAILED", "attempt " + attempts);
            throw nowLocked
                    ? new BusinessException(ErrorCode.ACCOUNT_LOCKED, "Too many failed attempts. Account locked.")
                    : new BusinessException(ErrorCode.BAD_CREDENTIALS, "Invalid e-mail or password");
        }
        c.setFailedLoginAttempts(0);
        c.setLockedUntil(null);
        customers.save(c);

        if (c.isMfaEnabled()) {
            otpService.generateAndSend(c.getEmail());
            audit.record(email, "LOGIN_PASSWORD_OK", "Customer", String.valueOf(c.getId()), "MFA_REQUIRED", null);
            return AuthResponse.mfa("OTP sent. Call /auth/verify-otp to complete login.");
        }
        return issue(c);
    }

    @Transactional
    public AuthResponse verifyOtp(VerifyOtpRequest r) {
        String email = r.email().trim().toLowerCase();
        Customer c = customers.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_OTP, "Invalid OTP"));
        try {
            otpService.verify(c.getEmail(), r.otp());
        } catch (BusinessException e) {
            audit.record(email, "OTP_VERIFY", "Customer", String.valueOf(c.getId()), e.getCode().name(), null);
            throw e;
        }
        return issue(c);
    }

    private AuthResponse issue(Customer c) {
        JwtService.IssuedToken token = jwtService.issue(c);
        audit.record(c.getEmail(), "LOGIN", "Customer", String.valueOf(c.getId()), "SUCCESS", null);
        return AuthResponse.token(token.value(), token.expiresInSeconds());
    }
}
