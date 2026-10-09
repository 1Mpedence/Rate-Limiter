package com.example.ratelimiter.algorithm;

public interface RateLimitAlgorithm {

    String name();
    RateLimitResult check(String key, long limit, long windowMs);
}
