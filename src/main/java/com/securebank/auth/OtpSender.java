package com.securebank.auth;

/** Delivery channel for one-time passwords (SMS / e-mail / push). Plug in SNS, SES, Twilio, etc. */
public interface OtpSender {
    void send(String destination, String otp);
}
