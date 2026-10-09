package com.example.ratelimiter.web;

import java.io.IOException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.example.ratelimiter.algorithm.RateLimitResult;
import com.example.ratelimiter.rules.RateLimitService;
import com.example.ratelimiter.rules.RateLimitService.Decision;
import com.example.ratelimiter.rules.Tier;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final RateLimitService service;
    private final boolean failOpen;

    public RateLimitInterceptor(RateLimitService service,
                                @Value("${ratelimiter.fail-open:true}") boolean failOpen) {
        this.service = service;
        this.failOpen = failOpen;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler)
            throws IOException {

        String api = req.getRequestURI().substring("/api/".length());   // "/api/search" -> "search"
        String user = req.getHeader("X-User-Id");
        if (user == null || user.isBlank()) {
            user = "anonymous";
        }

        Tier tier;
        try {
            tier = Tier.from(req.getHeader("X-Tier"));
        } catch (IllegalArgumentException e) {
            writeJson(resp, 400, "{\"error\":\"X-Tier must be FREE or PREMIUM\"}");
            return false;
        }

        Optional<Decision> decision;
        try {
            decision = service.check(user, tier, api);
        } catch (DataAccessException e) {
            log.warn("Redis unavailable, failing {}: {}", failOpen ? "open" : "closed", e.getMessage());
            if (failOpen) {
                resp.setHeader("X-RateLimit-Status", "bypassed");
                return true;
            }
            writeJson(resp, 503, "{\"error\":\"rate_limiter_unavailable\"}");
            return false;
        }

        if (decision.isEmpty()) {
            return true;    // no rule for this API: not limited
        }

        RateLimitResult result = decision.get().result();
        resp.setHeader("X-RateLimit-Limit", String.valueOf(decision.get().rule().limit()));
        resp.setHeader("X-RateLimit-Remaining", String.valueOf(result.remaining()));

        if (result.allowed()) {
            return true;
        }

        long retrySeconds = (long) Math.ceil(result.retryAfterMs() / 1000.0);
        resp.setHeader("Retry-After", String.valueOf(retrySeconds));
        writeJson(resp, 429, "{\"error\":\"rate_limit_exceeded\",\"retryAfterMs\":"
                + result.retryAfterMs() + "}");
        return false;
    }

    private static void writeJson(HttpServletResponse resp, int status, String json) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.getWriter().write(json);
    }
}
