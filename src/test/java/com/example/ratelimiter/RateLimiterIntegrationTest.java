package com.example.ratelimiter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "instance.id=test")
class RateLimiterIntegrationTest {

    @Container
    @ServiceConnection
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @Autowired
    TestRestTemplate http;

    private String userId;

    @BeforeEach
    void freshUser() {
        userId = "test-" + UUID.randomUUID();
    }

    @Test
    void burst_slidingWindow_neverExceedsLimit() throws Exception {
        setRule("FREE", "search", "sliding", 20, 60_000);
        assertThat(burst(200, userId, "FREE", "search")).isEqualTo(20);
    }

    @Test
    void burst_fixedWindow_neverExceedsLimit() throws Exception {
        setRule("FREE", "search", "fixed", 20, 60_000);
        alignToWindowStart(60_000);
        assertThat(burst(200, userId, "FREE", "search")).isEqualTo(20);
    }

    @Test
    void burst_tokenBucket_neverExceedsLimitByMore_thanRefillSlack() throws Exception {
        setRule("FREE", "search", "bucket", 20, 60_000);
        int allowed = burst(200, userId, "FREE", "search");
        assertThat(allowed).isBetween(20, 25);
    }

    @Test
    void counters_areIsolated_perUser() {
        setRule("FREE", "search", "bucket", 3, 60_000);
        String other = "test-" + UUID.randomUUID();

        repeat(3, () -> api("search", userId, "FREE"));
        assertThat(api("search", userId, "FREE").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(api("search", other, "FREE").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void counters_areIsolated_perApi() {
        setRule("FREE", "search", "bucket", 2, 60_000);
        setRule("FREE", "export", "sliding", 5, 60_000);

        repeat(2, () -> api("search", userId, "FREE"));
        assertThat(api("search", userId, "FREE").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(api("export", userId, "FREE").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void counters_areIsolated_perTier() {
        setRule("FREE", "search", "bucket", 2, 60_000);
        setRule("PREMIUM", "search", "bucket", 10, 60_000);

        repeat(2, () -> api("search", userId, "FREE"));
        assertThat(api("search", userId, "FREE").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(api("search", userId, "PREMIUM").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void windowReset_slidingWindow_allowsRequestsAfterWindowExpires() throws InterruptedException {
        setRule("FREE", "search", "sliding", 2, 1_000);

        repeat(2, () -> api("search", userId, "FREE"));
        assertThat(api("search", userId, "FREE").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        Thread.sleep(1_300);
        assertThat(api("search", userId, "FREE").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void windowReset_tokenBucket_allowsRequestsAfterRefill() throws InterruptedException {
        setRule("FREE", "search", "bucket", 2, 2_000);

        repeat(2, () -> api("search", userId, "FREE"));
        assertThat(api("search", userId, "FREE").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        Thread.sleep(1_100);
        assertThat(api("search", userId, "FREE").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void rejectedResponse_carriesRetryAfterHeader() {
        setRule("FREE", "search", "sliding", 1, 60_000);
        api("search", userId, "FREE");

        ResponseEntity<String> rejected = api("search", userId, "FREE");
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getHeaders().getFirst("Retry-After")).isNotNull();
    }

    @Test
    void allowedResponse_carriesRateLimitHeaders() {
        setRule("FREE", "search", "bucket", 10, 60_000);

        ResponseEntity<String> response = api("search", userId, "FREE");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("10");
        assertThat(response.getHeaders().getFirst("X-RateLimit-Remaining")).isNotNull();
    }

    private void setRule(String tier, String api, String algorithm, int limit, int windowMs) {
        String body = String.format(
                "{\"algorithm\":\"%s\",\"limit\":%d,\"windowMs\":%d}", algorithm, limit, windowMs);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        http.exchange("/admin/rules/" + tier + "/" + api,
                HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> api(String endpoint, String user, String tier) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", user);
        headers.set("X-Tier", tier);
        return http.exchange("/api/" + endpoint, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private void repeat(int times, Runnable action) {
        for (int i = 0; i < times; i++) action.run();
    }

    private int burst(int count, String user, String tier, String endpoint) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (api(endpoint, user, tier).getStatusCode() == HttpStatus.OK) {
                    allowed.incrementAndGet();
                }
            }));
        }
        ready.await();
        start.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();
        return allowed.get();
    }

    private static void alignToWindowStart(long windowMs) throws InterruptedException {
        long position = System.currentTimeMillis() % windowMs;
        if (position > windowMs * 0.90) {
            Thread.sleep(windowMs - position + 200);
        }
    }
}
