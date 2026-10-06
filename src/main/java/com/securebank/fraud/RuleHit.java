package com.securebank.fraud;

public record RuleHit(String ruleCode, Severity severity, String reason) {}
