package com.securebank.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "securebank.fraud")
public record FraudProperties(@DefaultValue("500000") BigDecimal highValueThreshold,
                              @DefaultValue("5") int velocityMaxTransactions,
                              @DefaultValue("60") long velocityWindowSeconds,
                              @DefaultValue("30") long geoWindowMinutes) {}
