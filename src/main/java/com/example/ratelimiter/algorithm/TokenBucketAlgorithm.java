package com.example.ratelimiter.algorithm;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class TokenBucketAlgorithm extends AbstractLuaAlgorithm {

    public TokenBucketAlgorithm(StringRedisTemplate redis) {
        super(redis, "scripts/token_bucket.lua");
    }

    @Override
    public String name() {
        return "bucket";
    }

    @Override
    public RateLimitResult check(String key, long limit, long windowMs) {
        double refillPerSec = limit * 1000.0 / windowMs;
        return run(key, String.valueOf(limit), String.valueOf(refillPerSec));
    }
}
