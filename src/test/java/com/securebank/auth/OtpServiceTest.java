package com.securebank.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.securebank.common.BusinessException;
import com.securebank.common.ErrorCode;
import com.securebank.config.OtpProperties;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OtpServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> ops;
    @Mock OtpSender sender;

    OtpService service;
    final String id = "a@b.com";

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
        service = new OtpService(redis, new OtpProperties(6, 300, 3, 900, false), sender);
    }

    private String generateAndCaptureOtp() {
        service.generateAndSend(id);
        ArgumentCaptor<String> otp = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(id), otp.capture());
        return otp.getValue();
    }

    @Test
    void generatesSixDigitCodeStoresOnlyHashWithTtl() {
        String otp = generateAndCaptureOtp();
        assertThat(otp).matches("\\d{6}");
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(ops).set(eq("otp:code:" + id), stored.capture(), eq(Duration.ofSeconds(300)));
        assertThat(stored.getValue()).doesNotContain(otp).hasSize(64);
    }

    @Test
    void blockedIdentityCannotRequestOtp() {
        when(redis.hasKey("otp:block:" + id)).thenReturn(true);
        assertThatThrownBy(() -> service.generateAndSend(id))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.TOO_MANY_ATTEMPTS));
    }

    @Test
    void correctOtpSucceedsAndIsConsumed() {
        String otp = generateAndCaptureOtp();
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(ops).set(eq("otp:code:" + id), stored.capture(), any(Duration.class));
        when(ops.get("otp:code:" + id)).thenReturn(stored.getValue());

        service.verify(id, otp);
        verify(redis).delete("otp:code:" + id);
    }

    @Test
    void expiredOtpIsRejected() {
        when(ops.get("otp:code:" + id)).thenReturn(null);
        assertThatThrownBy(() -> service.verify(id, "123456"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.OTP_EXPIRED));
    }

    @Test
    void wrongOtpCountsAttemptAndThirdFailureBlocks() {
        when(ops.get("otp:code:" + id)).thenReturn("somehash");
        when(ops.increment("otp:attempts:" + id)).thenReturn(1L, 2L, 3L);

        assertThatThrownBy(() -> service.verify(id, "000000"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_OTP));
        verify(redis).expire(eq("otp:attempts:" + id), any(Duration.class));
        assertThatThrownBy(() -> service.verify(id, "000000"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_OTP));
        assertThatThrownBy(() -> service.verify(id, "000000"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.TOO_MANY_ATTEMPTS));
        verify(ops).set(eq("otp:block:" + id), anyString(), eq(Duration.ofSeconds(900)));
    }
}
