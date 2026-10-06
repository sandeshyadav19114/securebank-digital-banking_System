package com.securebank.common;

public final class Masking {
    private Masking() {}

    /** Keeps the last {@code visible} characters, masks the rest. */
    public static String mask(String value, int visible) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (value.length() <= visible) {
            return "*".repeat(value.length());
        }
        return "*".repeat(value.length() - visible) + value.substring(value.length() - visible);
    }
}
