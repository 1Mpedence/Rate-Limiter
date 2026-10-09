package com.example.ratelimiter.algorithm;

import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class SlidingWindowLogAlgorithm extends AbstractLuaAlgorithm {

    public SlidingWindowLogAlgorithm(StringRedisTemplate redis) {
        super(redis, "scripts/sliding_window_log.lua");
    }

    @Override
    public String name() {
        return "sliding";
    }

    @Override
    public RateLimitResult check(String key, long limit, long windowMs) {
        String requestId = UUID.randomUUID().toString();
        return run(key, String.valueOf(limit), String.valueOf(windowMs), requestId);
    }
}
