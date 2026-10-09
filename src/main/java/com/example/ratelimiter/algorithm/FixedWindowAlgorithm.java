package com.example.ratelimiter.algorithm;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class FixedWindowAlgorithm extends AbstractLuaAlgorithm {

    public FixedWindowAlgorithm(StringRedisTemplate redis) {
        super(redis, "scripts/fixed_window.lua");
    }

    @Override
    public String name() {
        return "fixed";
    }

    @Override
    public RateLimitResult check(String key, long limit, long windowMs) {
        return run(key, String.valueOf(limit), String.valueOf(windowMs));
    }
}
