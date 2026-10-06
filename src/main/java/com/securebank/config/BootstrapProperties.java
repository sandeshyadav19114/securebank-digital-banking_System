package com.securebank.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Optional first-run admin user (dev / initial provisioning). */
@ConfigurationProperties(prefix = "securebank.bootstrap")
public record BootstrapProperties(String adminEmail, String adminPassword) {}
