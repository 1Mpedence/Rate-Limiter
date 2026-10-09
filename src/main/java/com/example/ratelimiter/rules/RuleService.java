package com.example.ratelimiter.rules;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

@Service
public class RuleService {

    private static final String HASH = "rl:rules";

    public static final java.util.List<String> APIS = java.util.List.of("search", "export");

    private static final Map<String, Rule> DEFAULTS = Map.of(
            field(Tier.FREE, "search"),      new Rule("bucket", 10, 60_000),
            field(Tier.PREMIUM, "search"),   new Rule("bucket", 100, 60_000),
            field(Tier.FREE, "export"),      new Rule("sliding", 2, 60_000),
            field(Tier.PREMIUM, "export"),   new Rule("sliding", 20, 60_000));

    private final StringRedisTemplate redis;

    public RuleService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @PostConstruct
    void seedDefaults() {
        DEFAULTS.forEach((f, rule) -> redis.opsForHash().putIfAbsent(HASH, f, rule.encode()));
    }

    public Optional<Rule> find(Tier tier, String api) {
        Object value = redis.opsForHash().get(HASH, field(tier, api));
        return value == null ? Optional.empty() : Optional.of(Rule.decode(value.toString()));
    }

    public void save(Tier tier, String api, Rule rule) {
        redis.opsForHash().put(HASH, field(tier, api), rule.encode());
    }

    public Map<String, Rule> all() {
        Map<String, Rule> result = new TreeMap<>();
        redis.opsForHash().entries(HASH)
                .forEach((k, v) -> result.put(k.toString(), Rule.decode(v.toString())));
        return result;
    }

    private static String field(Tier tier, String api) {
        return tier.name() + ":" + api;
    }
}
