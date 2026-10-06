package com.securebank.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "securebank.jwt")
public record JwtProperties(String secret,
                            @DefaultValue("securebank") String issuer,
                            @DefaultValue("15") long accessTokenMinutes) {}
