package com.example.ratelimiter.algorithm;

import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

abstract class AbstractLuaAlgorithm implements RateLimitAlgorithm {

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> script;

    AbstractLuaAlgorithm(StringRedisTemplate redis, String scriptPath) {
        this.redis = redis;
        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new ClassPathResource(scriptPath));
        this.script.setResultType(List.class);
    }

    protected RateLimitResult run(String key, String... args) {
        List<?> reply = redis.execute(script, List.of(key), (Object[]) args);
        if (reply.size() < 3) {
            throw new IllegalStateException("Unexpected reply from Redis script: " + reply);
        }
        boolean allowed = ((Number) reply.get(0)).longValue() == 1L;
        long remaining = ((Number) reply.get(1)).longValue();
        long retryAfterMs = ((Number) reply.get(2)).longValue();
        return new RateLimitResult(allowed, remaining, retryAfterMs);
    }
}
