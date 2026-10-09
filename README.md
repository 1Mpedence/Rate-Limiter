# Distributed Rate Limiter Service

A multi-instance rate limiter built with Java, Spring Boot and Redis. Several copies
of the service sit behind nginx and still enforce one shared limit per user, because all
counting happens inside Redis as atomic Lua scripts.

Limits depend on **who** is calling (Free or Premium tier) and **which API** they call,
and can be changed at runtime through an admin API.

## Architecture

```
                         +--> app1 --+
  client --> nginx ------+--> app2 --+--> Redis  (counters + rules, one shared copy)
            (round-robin)+--> app3 --+
```

- **nginx** receives every request and hands it to one of three identical app copies in turn.
- **The apps hold no counters.** Every limit decision is made by a Lua script running inside Redis, so
  any copy can answer any request and they all agree.
- **Redis** stores both the counters and the rules table.

## How a request is handled

1. A request arrives at `/api/search` or `/api/export` with `X-User-Id` and `X-Tier` headers.
2. `RateLimitInterceptor` runs before the controller. It looks up the rule for (tier, API).
3. `RateLimitService` picks the algorithm named by that rule and runs its Lua script in Redis.
4. The script answers *allowed / remaining / retry-after*. Allowed requests continue to the API;
   rejected ones get HTTP 429 with a `Retry-After` header.

## Algorithms

All three take the same inputs (a limit and a window), so any rule can use any of them.

| | Fixed window | Sliding window log | Token bucket |
|---|---|---|---|
| Idea | counter that resets each time slot | log of every request time in the last window | bucket refilled at a steady rate; each request takes a token |
| Memory per user | 1 counter | 1 entry per request | 2 numbers |
| Accuracy | rough (a burst across a window boundary gets 2x) | exact | good, allows bursts by design |
| Best for | cheap, rough limits | strict limits on expensive operations | bursty but fair traffic |

Default rules (illustrative numbers, editable at runtime):

| | search (token bucket) | export (sliding window) |
|---|---|---|
| Free | 10 per minute | 2 per minute |
| Premium | 100 per minute | 20 per minute |

For the token bucket, "limit per window" becomes: bucket size = limit, refill rate = limit / window.

## Design decisions

- **Lua scripts for atomicity.** Check-and-update must be one indivisible step. Otherwise two instances
  can both read "49 of 50 used" and both allow a request. Redis runs one script at a time.
- **Time comes from Redis (`TIME`), not from the app instances,** so their clocks cannot disagree.
- **Rules live in Redis, not in app memory,** so an admin change reaches all instances at once.
- **The algorithm name is part of the Redis key** (`rl:<algorithm>:<user>:<api>`), so counters stored
  in different shapes never collide when a rule switches algorithm.
- **Strategy pattern.** One `RateLimitAlgorithm` interface, one class per algorithm. Adding an algorithm
  means one Lua script and one small class.
- **Short Redis timeouts** (200 ms) so a dead Redis cannot make requests hang.
- **Fail open by default.** If Redis is unreachable the request is let through and the response carries
  `X-RateLimit-Status: bypassed`. Set `FAIL_OPEN=false` to reject with 503 instead. Open favours
  availability; closed favours never exceeding a limit.

## Run it

Requires Docker.

```
docker compose up --build
```

Everything is reachable through nginx on port 8080.

```
# Free user, export API: limit is 2 per minute, so the 3rd request is rejected
for i in 1 2 3; do
  curl -s -i -H "X-User-Id: alice" -H "X-Tier: FREE" localhost:8080/api/export | grep -E "HTTP/|X-Served-By"
done
```

`X-Served-By` shows which app copy handled each request.

### Admin API

```
# See all rules
curl localhost:8080/admin/rules

# Change a rule while the service is running (no restart)
curl -X PUT -H "Content-Type: application/json" \
  -d '{"algorithm":"bucket","limit":200,"windowMs":60000}' \
  localhost:8080/admin/rules/PREMIUM/search
```

Algorithms: `fixed`, `sliding`, `bucket`. Tiers: `FREE`, `PREMIUM`. APIs: `search`, `export`.

## Tests

```
./mvnw test
```

Requires Docker (Testcontainers starts a Redis container automatically).

Two layers:

**Integration tests** (`RateLimiterIntegrationTest`) run against a real Redis container and verify:

- A burst of concurrent requests never gets more than the limit through (all three algorithms).
- Counters are separate per user, per API, and per tier.
- Requests are allowed again after the window expires.
- Rejected responses carry `Retry-After`; allowed responses carry `X-RateLimit-*` headers.

**Unit tests** (`RateLimitServiceTest`, `RateLimitInterceptorTest`) use Mockito to cover algorithm
selection, fail-open/fail-closed behaviour, invalid headers, and `Retry-After` rounding.

## Benchmarks

k6 load tests live in `benchmarks/`. Full method, setup and tables are in [BENCHMARKS.md](BENCHMARKS.md).
Headlines, measured on a 16-core M4 Max laptop with Docker Desktop (load generator on the same machine):

- **Held load:** 20,000 requests/s for 3 minutes (about 3.6 million requests) with no failures; p99 3.6 to 5.3 ms
  depending on the algorithm. 10,000 requests/s for 5 minutes, p99 1.4 to 1.8 ms.
- **Peak:** up to 30,000 requests/s in 20-second steps, p99 8.4 to 11.1 ms, no failures in the clean repeat runs.
  The ceiling was not reached. One early run showed failures at 25,000 to 30,000 and was not reproduced; see
  BENCHMARKS.md.
- **Correctness under concurrency:** 1,000 simultaneous requests against a limit of 50 admitted exactly 50
  across three instances, for all three algorithms.
- **Memory per busy user:** about 100 bytes for the token bucket against about 1.7 MB for the sliding window
  log (one user at about 500 requests/s).

## Known limitations

- **Redis is a single point of failure.** Production would use replicas or a cluster, and a deliberate
  fail-open/closed decision (see above).
- **The admin API has no authentication.**
- **The tier is read from a request header,** which a caller could fake. A real system would take it from
  a verified login token or a user database.
- **Rules are read from Redis on every request,** an extra round trip. A short in-memory cache would be
  an optimisation, at the cost of rule changes taking a moment to spread.
- **The sliding window log stores one entry per allowed request,** so memory grows with the limit.

## Project layout

```
src/main/java/com/example/ratelimiter/
  algorithm/   RateLimitAlgorithm interface, one class per algorithm, shared Lua runner
  rules/       Tier, Rule, rules table in Redis, RateLimitService (picks the algorithm)
  web/         interceptor (the guard), demo APIs, admin API, X-Served-By filter
src/main/resources/scripts/   the three Lua scripts
src/test/java/               integration and unit tests
nginx/nginx.conf              load balancer config
docker-compose.yml            Redis + 3 app copies + nginx
benchmarks/                   k6 load tests (step, max throughput, burst)
BENCHMARKS.md                 how to run them, how to read them, results
```