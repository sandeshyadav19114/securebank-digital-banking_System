package com.securebank.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "securebank.bank")
public record BankProperties(@DefaultValue("1000000") BigDecimal perTransactionLimit,
                             @DefaultValue("10000") BigDecimal currentOverdraftLimit,
                             @DefaultValue("GL0000000001") String glAccountNumber) {}
