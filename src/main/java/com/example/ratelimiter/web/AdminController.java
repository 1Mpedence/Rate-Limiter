package com.example.ratelimiter.web;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ratelimiter.rules.RateLimitService;
import com.example.ratelimiter.rules.Rule;
import com.example.ratelimiter.rules.RuleService;
import com.example.ratelimiter.rules.Tier;

@RestController
@RequestMapping("/admin/rules")
public class AdminController {

    public record RuleRequest(String algorithm, long limit, long windowMs) {
    }

    private final RuleService rules;
    private final RateLimitService limiter;

    public AdminController(RuleService rules, RateLimitService limiter) {
        this.rules = rules;
        this.limiter = limiter;
    }

    @GetMapping
    public Map<String, Rule> list() {
        return rules.all();
    }

    @PutMapping("/{tier}/{api}")
    public ResponseEntity<?> update(@PathVariable String tier,
                                    @PathVariable String api,
                                    @RequestBody RuleRequest body) {
        Tier parsedTier;
        try {
            parsedTier = Tier.from(tier);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "tier must be FREE or PREMIUM"));
        }
        if (!RuleService.APIS.contains(api)) {
            return ResponseEntity.badRequest().body(Map.of("error", "api must be one of " + RuleService.APIS));
        }
        if (body.algorithm() == null || !limiter.isKnownAlgorithm(body.algorithm())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "algorithm must be one of " + limiter.algorithmNames()));
        }
        if (body.limit() <= 0 || body.windowMs() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "limit and windowMs must be positive"));
        }

        Rule rule = new Rule(body.algorithm(), body.limit(), body.windowMs());
        rules.save(parsedTier, api, rule);
        return ResponseEntity.ok(rule);
    }
}
