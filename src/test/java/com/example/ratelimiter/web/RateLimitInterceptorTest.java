package com.example.ratelimiter.web;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.example.ratelimiter.algorithm.RateLimitResult;
import com.example.ratelimiter.rules.RateLimitService;
import com.example.ratelimiter.rules.RateLimitService.Decision;
import com.example.ratelimiter.rules.Rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {

    @Mock RateLimitService service;

    MockHttpServletRequest req;
    MockHttpServletResponse resp;

    @BeforeEach
    void setUp() {
        req = new MockHttpServletRequest("GET", "/api/search");
        resp = new MockHttpServletResponse();
        req.addHeader("X-User-Id", "user1");
        req.addHeader("X-Tier", "FREE");
    }

    @Test
    void allowedRequest_returnsTrue_andDoesNotSetErrorStatus() throws Exception {
        when(service.check(anyString(), any(), anyString()))
                .thenReturn(Optional.of(decision(true, 9, 0)));

        assertThat(interceptor(true).preHandle(req, resp, null)).isTrue();
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectedRequest_returnsFalse_andSets429() throws Exception {
        when(service.check(anyString(), any(), anyString()))
                .thenReturn(Optional.of(decision(false, 0, 5_000)));

        assertThat(interceptor(true).preHandle(req, resp, null)).isFalse();
        assertThat(resp.getStatus()).isEqualTo(429);
    }

    @Test
    void rejectedResponse_setsRetryAfterHeader_roundedUpToSeconds() throws Exception {
        when(service.check(anyString(), any(), anyString()))
                .thenReturn(Optional.of(decision(false, 0, 5_001)));

        interceptor(true).preHandle(req, resp, null);

        // 5001 ms → ceil(5.001) = 6 s
        assertThat(resp.getHeader("Retry-After")).isEqualTo("6");
    }

    @Test
    void allowedResponse_setsRateLimitHeaders() throws Exception {
        when(service.check(anyString(), any(), anyString()))
                .thenReturn(Optional.of(decision(true, 7, 0)));

        interceptor(true).preHandle(req, resp, null);

        assertThat(resp.getHeader("X-RateLimit-Limit")).isEqualTo("10");
        assertThat(resp.getHeader("X-RateLimit-Remaining")).isEqualTo("7");
    }

    @Test
    void noRuleForApi_passesThrough() throws Exception {
        when(service.check(anyString(), any(), anyString())).thenReturn(Optional.empty());

        assertThat(interceptor(true).preHandle(req, resp, null)).isTrue();
    }

    @Test
    void missingUserId_defaultsToAnonymous_andStillEnforcesLimit() throws Exception {
        req.removeHeader("X-User-Id");
        when(service.check(anyString(), any(), anyString()))
                .thenReturn(Optional.of(decision(true, 9, 0)));

        assertThat(interceptor(true).preHandle(req, resp, null)).isTrue();
    }

    @Test
    void invalidTierHeader_returns400() throws Exception {
        req.removeHeader("X-Tier");
        req.addHeader("X-Tier", "GOLD");

        assertThat(interceptor(true).preHandle(req, resp, null)).isFalse();
        assertThat(resp.getStatus()).isEqualTo(400);
    }

    @Test
    void redisDown_failOpen_letsThroughWithBypassedHeader() throws Exception {
        when(service.check(anyString(), any(), anyString()))
                .thenThrow(new QueryTimeoutException("timeout"));

        assertThat(interceptor(true).preHandle(req, resp, null)).isTrue();
        assertThat(resp.getHeader("X-RateLimit-Status")).isEqualTo("bypassed");
    }

    @Test
    void redisDown_failClosed_returns503() throws Exception {
        when(service.check(anyString(), any(), anyString()))
                .thenThrow(new QueryTimeoutException("timeout"));

        assertThat(interceptor(false).preHandle(req, resp, null)).isFalse();
        assertThat(resp.getStatus()).isEqualTo(503);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private RateLimitInterceptor interceptor(boolean failOpen) {
        return new RateLimitInterceptor(service, failOpen);
    }

    private Decision decision(boolean allowed, long remaining, long retryAfterMs) {
        return new Decision(
                new Rule("bucket", 10, 60_000),
                new RateLimitResult(allowed, remaining, retryAfterMs));
    }
}
