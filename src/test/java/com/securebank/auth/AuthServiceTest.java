package com.securebank.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.securebank.audit.AuditService;
import com.securebank.auth.AuthDtos.AuthResponse;
import com.securebank.auth.AuthDtos.LoginRequest;
import com.securebank.auth.AuthDtos.RegisterRequest;
import com.securebank.auth.AuthDtos.VerifyOtpRequest;
import com.securebank.common.BusinessException;
import com.securebank.common.ErrorCode;
import com.securebank.config.LockoutProperties;
import com.securebank.customer.Customer;
import com.securebank.customer.CustomerRepository;
import com.securebank.security.JwtService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    @Mock CustomerRepository customers;
    @Mock PasswordEncoder encoder;
    @Mock OtpService otpService;
    @Mock JwtService jwtService;
    @Mock AuditService audit;

    final Clock clock = Clock.fixed(Instant.parse("2026-01-01T10:00:00Z"), ZoneOffset.UTC);
    AuthService service;
    Customer customer;

    @BeforeEach
    void setUp() {
        when(encoder.encode(anyString())).thenReturn("hash");
        service = new AuthService(customers, encoder, otpService, jwtService, new LockoutProperties(3, 30), audit, clock);
        customer = new Customer();
        customer.setEmail("a@b.com");
        customer.setPasswordHash("hash");
        when(customers.findByEmailIgnoreCase("a@b.com")).thenReturn(Optional.of(customer));
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(customers.existsByEmailIgnoreCase("a@b.com")).thenReturn(true);
        assertThatThrownBy(() -> service.register(new RegisterRequest("A", "a@b.com", "9999999999", "Passw0rd!xx")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.DUPLICATE));
    }

    @Test
    void registerHashesPassword() {
        when(customers.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        service.register(new RegisterRequest("A", "A@B.com", "9999999999", "Passw0rd!xx"));
        verify(encoder).encode("Passw0rd!xx");
    }

    @Test
    void unknownUserGetsGenericError() {
        when(customers.findByEmailIgnoreCase("x@y.com")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.login(new LoginRequest("x@y.com", "pw")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.BAD_CREDENTIALS));
        verify(encoder).matches("pw", "hash"); // dummy compare keeps timing uniform
    }

    @Test
    void wrongPasswordIncrementsCounterThenLocksOnThreshold() {
        when(encoder.matches("bad", "hash")).thenReturn(false);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service.login(new LoginRequest("a@b.com", "bad")))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.BAD_CREDENTIALS));
        }
        assertThat(customer.getFailedLoginAttempts()).isEqualTo(2);

        assertThatThrownBy(() -> service.login(new LoginRequest("a@b.com", "bad")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ACCOUNT_LOCKED));
        assertThat(customer.getLockedUntil()).isEqualTo(clock.instant().plusSeconds(30 * 60));
    }

    @Test
    void lockedAccountRejectedEvenWithCorrectPassword() {
        customer.setLockedUntil(clock.instant().plusSeconds(60));
        when(encoder.matches("good", "hash")).thenReturn(true);
        assertThatThrownBy(() -> service.login(new LoginRequest("a@b.com", "good")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ACCOUNT_LOCKED));
        verify(otpService, never()).generateAndSend(anyString());
    }

    @Test
    void expiredLockDoesNotBlockLogin() {
        customer.setLockedUntil(clock.instant().minusSeconds(1));
        when(encoder.matches("good", "hash")).thenReturn(true);
        AuthResponse r = service.login(new LoginRequest("a@b.com", "good"));
        assertThat(r.mfaRequired()).isTrue();
    }

    @Test
    void correctPasswordWithMfaSendsOtpAndIssuesNoToken() {
        customer.setFailedLoginAttempts(2);
        when(encoder.matches("good", "hash")).thenReturn(true);
        AuthResponse r = service.login(new LoginRequest("a@b.com", "good"));
        assertThat(r.mfaRequired()).isTrue();
        assertThat(r.accessToken()).isNull();
        assertThat(customer.getFailedLoginAttempts()).isZero();
        verify(otpService).generateAndSend("a@b.com");
        verify(jwtService, never()).issue(any());
    }

    @Test
    void withoutMfaTokenIsIssuedImmediately() {
        customer.setMfaEnabled(false);
        when(encoder.matches("good", "hash")).thenReturn(true);
        when(jwtService.issue(customer)).thenReturn(new JwtService.IssuedToken("jwt", 900));
        AuthResponse r = service.login(new LoginRequest("a@b.com", "good"));
        assertThat(r.accessToken()).isEqualTo("jwt");
        assertThat(r.tokenType()).isEqualTo("Bearer");
    }

    @Test
    void verifyOtpIssuesTokenOnSuccess() {
        when(jwtService.issue(customer)).thenReturn(new JwtService.IssuedToken("jwt", 900));
        AuthResponse r = service.verifyOtp(new VerifyOtpRequest("a@b.com", "123456"));
        verify(otpService).verify("a@b.com", "123456");
        assertThat(r.accessToken()).isEqualTo("jwt");
    }

    @Test
    void verifyOtpPropagatesFailure() {
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.INVALID_OTP, "bad")).when(otpService).verify(anyString(), anyString());
        assertThatThrownBy(() -> service.verifyOtp(new VerifyOtpRequest("a@b.com", "000000")))
                .isInstanceOf(BusinessException.class);
        verify(jwtService, never()).issue(any());
    }
}
