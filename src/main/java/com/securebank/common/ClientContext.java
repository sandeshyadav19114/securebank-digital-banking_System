package com.securebank.common;

import jakarta.servlet.http.HttpServletRequest;

/** Network context of the caller, used by fraud rules and audit. */
public record ClientContext(String ip, String country) {

    public static ClientContext from(HttpServletRequest request, String countryHeader) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
        String country = (countryHeader == null || countryHeader.isBlank())
                ? null : countryHeader.trim().toUpperCase();
        return new ClientContext(ip, country);
    }
}
