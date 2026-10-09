package com.example.ratelimiter.rules;

public enum Tier {
    FREE, PREMIUM;

    public static Tier from(String text) {
        if (text == null || text.isBlank()) {
            return FREE;
        }
        return valueOf(text.trim().toUpperCase());
    }
}
