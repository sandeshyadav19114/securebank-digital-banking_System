package com.securebank.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "securebank.otp")
public record OtpProperties(@DefaultValue("6") int length,
                            @DefaultValue("300") long ttlSeconds,
                            @DefaultValue("3") int maxAttempts,
                            @DefaultValue("900") long blockSeconds,
                            @DefaultValue("false") boolean logPlaintext) {}
