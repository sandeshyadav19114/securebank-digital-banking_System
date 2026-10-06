package com.securebank.auth;

import com.securebank.common.BusinessException;
import com.securebank.common.ErrorCode;
import com.securebank.config.OtpProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis-backed OTP:
 *   otp:code:{id}     SHA-256(otp) with TTL           (the code itself is never stored in clear)
 *   otp:attempts:{id} failed verification counter with TTL
 *   otp:block:{id}    present while the identity is throttled
 */
@Service
@RequiredArgsConstructor
public class OtpService {

    private final StringRedisTemplate redis;
    private final OtpProperties props;
    private final OtpSender sender;
    private final SecureRandom random = new SecureRandom();

    public void generateAndSend(String identity) {
        if (Boolean.TRUE.equals(redis.hasKey(blockKey(identity)))) {
            throw new BusinessException(ErrorCode.TOO_MANY_ATTEMPTS, "Too many attempts. Try again later.");
        }
        int bound = (int) Math.pow(10, props.length());
        String otp = String.format("%0" + props.length() + "d", random.nextInt(bound));
        redis.opsForValue().set(codeKey(identity), hash(identity, otp), Duration.ofSeconds(props.ttlSeconds()));
        redis.delete(attemptsKey(identity));
        sender.send(identity, otp);
    }

    public void verify(String identity, String otp) {
        if (Boolean.TRUE.equals(redis.hasKey(blockKey(identity)))) {
            throw new BusinessException(ErrorCode.TOO_MANY_ATTEMPTS, "Too many attempts. Try again later.");
        }
        String stored = redis.opsForValue().get(codeKey(identity));
        if (stored == null) {
            throw new BusinessException(ErrorCode.OTP_EXPIRED, "OTP expired or not requested");
        }
        boolean match = MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8),
                hash(identity, otp).getBytes(StandardCharsets.UTF_8));
        if (match) {
            redis.delete(codeKey(identity));
            redis.delete(attemptsKey(identity));
            return;
        }
        Long attempts = redis.opsForValue().increment(attemptsKey(identity));
        long used = attempts == null ? 1 : attempts;
        if (used == 1) {
            redis.expire(attemptsKey(identity), Duration.ofSeconds(props.ttlSeconds()));
        }
        if (used >= props.maxAttempts()) {
            redis.opsForValue().set(blockKey(identity), "1", Duration.ofSeconds(props.blockSeconds()));
            redis.delete(codeKey(identity));
            redis.delete(attemptsKey(identity));
            throw new BusinessException(ErrorCode.TOO_MANY_ATTEMPTS, "Too many invalid OTPs. Temporarily blocked.");
        }
        throw new BusinessException(ErrorCode.INVALID_OTP,
                "Invalid OTP. Attempts remaining: " + (props.maxAttempts() - used));
    }

    private static String hash(String identity, String otp) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest((identity + ":" + otp).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String codeKey(String id) { return "otp:code:" + id; }
    private static String attemptsKey(String id) { return "otp:attempts:" + id; }
    private static String blockKey(String id) { return "otp:block:" + id; }
}
