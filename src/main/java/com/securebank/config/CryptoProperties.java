package com.securebank.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "securebank.crypto")
public record CryptoProperties(String aesKey) {}
