# Distributed Rate Limiter
![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-brightgreen)
![Redis](https://img.shields.io/badge/Redis-7-red)

A distributed, tier-aware rate limiter backed by Redis. Three algorithms behind a unified interface, atomic operations via Lua scripts, horizontally scalable behind nginx, rules changeable at runtime with no restart.

## Why I built this

Rate limiters are one of those components that look simple until you try to build one correctly. The interesting problems show up fast: how do you keep a counter consistent when three instances of your service are running behind a load balancer? How do you avoid race conditions without locking in Java (which only works inside one JVM)? And what does "fair" actually mean when one user is bursty and another is steady?

I also wanted to understand the algorithm tradeoffs firsthand rather than just memorising them. Token bucket's lazy refill math, fixed window's boundary problem, sliding window log's memory cost — these only stick when you implement them.

## Features

- 3 algorithms: token bucket, fixed window, sliding window log
- Tier-based limits — FREE and PREMIUM users get different quotas per API
- All state in Redis, atomic via Lua — race conditions across instances are impossible
- Admin REST API for runtime rule changes, no service restart needed
- Configurable fail-open / fail-closed behaviour when Redis is unreachable
- Runs with `docker compose up` — nginx in front, three app copies behind it, one Redis
- JUnit + Testcontainers test suite
- k6 benchmark suite included

## Architecture

```
Client → nginx (round-robin) → app1 ┐
                              → app2 ├──→ Redis (counters + rules)
                              → app3 ┘
```

Three identical Spring Boot instances, all stateless. Every rate limit decision — including reading the rule and updating the counter — happens inside Redis as a single atomic Lua script. The apps hold no counters and share no JVM state.

Request flow:

```
Request → RateLimitInterceptor
            → RateLimitService.check(user, tier, api)
                → RuleService.find(tier, api)  // reads rule from Redis hash
                → algorithm.check(key, limit, windowMs)
                    → Lua script in Redis      // atomic check + increment
            → allowed / remaining / retry-after
```

## Algorithm comparison

| | Token bucket | Fixed window | Sliding window log |
|---|---|---|---|
| Memory per user | O(1) — 2 numbers | O(1) — 2 values | O(N) — 1 entry per request |
| Accuracy | Good | Rough (2x burst at boundary) | Exact |
| Allows bursts? | Yes, by design | At window boundary | No |
| Best for | General APIs, bursty but fair traffic | Cheap rough limits | Strict limits on expensive operations |

If you don't have a specific reason to choose otherwise, token bucket is the right default. It handles realistic traffic patterns well and the math is exact. Fixed window is a common mistake — it lets a user make 2× the limit by timing requests across a window boundary. Sliding window log is the most correct but stores every request timestamp, which gets expensive if limits are high.

## Quick start

Requires Docker.

```bash
docker compose up --build
```

nginx is on `localhost:8080`. Send requests as any user:

```bash
# First two requests go through (limit is 2 per minute for FREE export)
curl -s -i -H "X-User-Id: alice" -H "X-Tier: FREE" localhost:8080/api/export

# Third is rejected
curl -s -i -H "X-User-Id: alice" -H "X-Tier: FREE" localhost:8080/api/export
```

On the rejected response:
```
HTTP/1.1 429 Too Many Requests
Retry-After: 47
X-RateLimit-Limit: 2
X-RateLimit-Remaining: 0
```

`X-Served-By` on every response shows which app copy handled it, so you can see nginx distributing requests.

## API

### Rate-limited endpoints

```
GET /api/search   — limited by tier, token bucket by default
GET /api/export   — limited by tier, sliding window by default
```

Headers on every response:
- `X-RateLimit-Limit` — the limit that applies
- `X-RateLimit-Remaining` — how many are left right now
- `Retry-After` (429 only) — seconds to wait before retrying
- `X-Served-By` — which app instance answered

### Admin

```
GET  /admin/rules              — list all current rules
PUT  /admin/rules/{tier}/{api} — update a rule at runtime
```

Example — raise the PREMIUM search limit to 500 per minute:

```bash
curl -X PUT -H "Content-Type: application/json" \
  -d '{"algorithm":"bucket","limit":500,"windowMs":60000}' \
  localhost:8080/admin/rules/PREMIUM/search
```

Change takes effect immediately on all three instances — rules live in Redis, not in app memory.

Tiers: `FREE`, `PREMIUM`. APIs: `search`, `export`. Algorithms: `bucket`, `fixed`, `sliding`.

## Benchmarks

k6 load tests in `benchmarks/`. Run them inside Docker so traffic does not cross Docker Desktop's host-to-VM bridge:

```bash
# Step test: ramps from 500 to 6,000 req/s, 20 seconds per step
docker compose run --rm k6 run /benchmarks/step.js

# Same, different algorithms
docker compose run --rm -e ALGO=sliding k6 run /benchmarks/step.js

# Raw ceiling
docker compose run --rm k6 run /benchmarks/max.js

# 1,000 concurrent requests against a limit of 50
docker compose run --rm k6 run /benchmarks/burst.js
```

Fill results into `BENCHMARKS.md` after running. Report throughput and p99 from the same run and the same load level. Run at least 3 times and discard the first (JIT warm-up).

## Design decisions

### Why Lua in Redis and not Java locks?

`synchronized` and `ReentrantLock` only protect state inside one JVM. With three instances behind nginx, a Java lock does nothing — the other two instances don't know it exists.

The lock needs to live in shared state. Redis executes Lua scripts atomically: the entire script runs as a single uninterruptible operation no matter how many clients are connected. It gives the same guarantee as a `synchronized` block, except the lock is in Redis, which all instances share.

### Why use Redis `TIME` instead of the app's clock?

If each app instance supplies its own timestamp, three JVMs with slightly different clocks will disagree on window boundaries. A request that one instance puts in window N might land in window N+1 on another instance, depending on which app nginx routed it to.

`redis.call('TIME')` inside the Lua script returns the Redis server's clock. All three instances use the same clock automatically.

### Why fail-open by default?

When Redis goes down, the rate limiter has two options: let requests through (fail-open) or reject them with 503 (fail-closed).

Fail-open means limits are not enforced during a Redis outage, but your API stays up. Fail-closed means limits are always enforced, but a Redis outage takes down your entire API.

For most services, availability beats limit enforcement. The app sets `X-RateLimit-Status: bypassed` on responses during a Redis outage so you can monitor it. Set `FAIL_OPEN=false` as an environment variable (or edit it directly in `docker-compose.yml`) to flip the behaviour.

### Why is the algorithm name part of the Redis key?

A token bucket stores two floats in a Redis hash. A sliding window log stores a sorted set. If a rule switches from `bucket` to `sliding` for a user who already has data, the old data is in the wrong shape and the new algorithm will read garbage.

Prefixing the key with the algorithm name (`rl:bucket:alice:search` vs `rl:sliding:alice:search`) means each algorithm writes its own namespace. Switching algorithms starts a clean counter automatically.

### Why tiers?

Every real paid API has different limits for free and paid users. Building the tier distinction into the rule model (rather than bolting it on later) keeps the key space and rule lookup consistent. Adding a new tier is one enum value and a new row in the rules table.

## Tests

```bash
mvn test
```

Requires Docker (Testcontainers starts its own Redis container).

**Integration tests** (`RateLimiterIntegrationTest`) — real Redis, real HTTP, concurrent threads:
- Burst of 200 concurrent requests never gets more than the limit through, all three algorithms
- Counters are isolated per user, per API, per tier
- Requests are allowed again after the window expires
- Rejected responses carry `Retry-After`; allowed responses carry `X-RateLimit-*` headers

**Unit tests** (`RateLimitServiceTest`, `RateLimitInterceptorTest`) — Mockito, no Redis needed:
- Algorithm selection and unknown-algorithm error path
- Fail-open and fail-closed behaviour under Redis failure
- Invalid tier header, missing user ID, `Retry-After` rounding

## What I'd add in v2

- **Admin authentication** — right now any caller who can reach port 8080 can rewrite limits. HTTP Basic Auth at minimum, a proper API key in production.
- **Prometheus metrics** — a counter on allowed/rejected per (tier, api, algorithm) would make this observable. Micrometer is already on the classpath via Actuator.
- **Local rule cache** — rules are read from Redis on every request. A short in-memory cache (5–10 second TTL) would cut that round trip. The tradeoff is rule changes take a moment to propagate.
- **Redis cluster** — single Redis is a single point of failure. Replicas for reads, cluster for writes.
- **Sliding window counter** — an approximate sliding window that uses two fixed-window counters and linear interpolation. Same O(1) memory as fixed window, much better accuracy. Worth it if the sorted-set memory cost of the log becomes a problem.

## Project layout

```
src/main/java/com/example/ratelimiter/
├── algorithm/          # RateLimitAlgorithm interface, one class per algorithm, shared Lua runner
├── rules/              # Tier, Rule, RuleService (rules table in Redis), RateLimitService
├── web/                # RateLimitInterceptor, ApiController, AdminController, InstanceHeaderFilter
└── RateLimiterApplication.java

src/main/resources/
├── application.yml
└── scripts/            # Lua scripts (one per algorithm)
    ├── token_bucket.lua
    ├── fixed_window.lua
    └── sliding_window_log.lua

src/test/               # JUnit + Testcontainers tests
nginx/nginx.conf        # round-robin load balancer config
docker-compose.yml      # Redis + 3 app copies + nginx + optional k6
benchmarks/             # k6 step, max throughput, and burst tests
BENCHMARKS.md           # methodology, how to read results, results table
```
