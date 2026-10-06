package com.securebank.auth;

import com.securebank.common.Masking;
import com.securebank.config.OtpProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Default sender: logs only. The plaintext OTP is printed ONLY if securebank.otp.log-plaintext=true (dev). */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoggingOtpSender implements OtpSender {

    private final OtpProperties props;

    @Override
    public void send(String destination, String otp) {
        if (props.logPlaintext()) {
            log.warn("[DEV ONLY] OTP for {} is {}", destination, otp);
        } else {
            log.info("OTP dispatched to {} (value hidden)", Masking.mask(destination, 6));
        }
    }
}
