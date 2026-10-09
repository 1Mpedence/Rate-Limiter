package com.example.ratelimiter.algorithm;

public record RateLimitResult(boolean allowed, long remaining, long retryAfterMs) {
}
