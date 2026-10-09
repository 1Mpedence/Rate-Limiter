package com.example.ratelimiter.rules;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.example.ratelimiter.algorithm.RateLimitAlgorithm;
import com.example.ratelimiter.algorithm.RateLimitResult;

@Service
public class RateLimitService {

    public record Decision(Rule rule, RateLimitResult result) {
    }

    private final RuleService rules;
    private final Map<String, RateLimitAlgorithm> algorithms;

    public RateLimitService(RuleService rules, List<RateLimitAlgorithm> all) {
        this.rules = rules;
        this.algorithms = all.stream()
                .collect(Collectors.toMap(RateLimitAlgorithm::name, Function.identity()));
    }

    public Optional<Decision> check(String userId, Tier tier, String api) {
        Optional<Rule> found = rules.find(tier, api);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Rule rule = found.get();
        RateLimitAlgorithm algorithm = algorithms.get(rule.algorithm());
        if (algorithm == null) {
            throw new IllegalStateException("Rule names unknown algorithm: " + rule.algorithm());
        }
        String key = "rl:" + rule.algorithm() + ":" + userId + ":" + api;
        return Optional.of(new Decision(rule, algorithm.check(key, rule.limit(), rule.windowMs())));
    }

    public boolean isKnownAlgorithm(String name) {
        return algorithms.containsKey(name);
    }

    public Set<String> algorithmNames() {
        return algorithms.keySet();
    }
}
