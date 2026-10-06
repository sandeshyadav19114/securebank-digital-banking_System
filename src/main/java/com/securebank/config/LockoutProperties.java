package com.securebank.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "securebank.lockout")
public record LockoutProperties(@DefaultValue("5") int maxFailedAttempts,
                                @DefaultValue("30") long lockMinutes) {}
