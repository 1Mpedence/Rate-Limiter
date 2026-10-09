# Benchmarks

Load tests written with [k6](https://k6.io). They run **inside the Docker network**, so
traffic does not cross Docker Desktop's host-to-VM bridge (which slows requests down and
would distort the numbers).

Everything below was measured on 7 and 8 October 2026 on the laptop described under
"Setup". k6, nginx, three app copies and Redis all shared that one machine, so these are laptop
numbers, not server numbers. Where a figure comes from a single run it says so.

## What each test answers

| Script | Question it answers |
|---|---|
| `benchmarks/step.js` | At each load level (requests per second), how fast are the answers, and do any fail? **This is the main test.** |
| `benchmarks/max.js` | What is the raw ceiling in requests per second when users send as fast as the system answers? (Written, not run for these results.) |
| `benchmarks/burst.js` | When 1,000 requests arrive at the same instant for one user, does exactly the limit get through? |

The throughput tests use a limit of 1,000,000 per minute so that no request is rejected: they
measure how fast the **system** is on the allowed path, not how fast it can say "no". Traffic is spread
over 10,000 different user ids (`USERS=10000`) unless stated, so it hits many Redis keys instead of one.
All throughput runs call `/api/search` as a Free user.

## Before you run

1. Docker Desktop, Settings, Resources: give Docker as many CPUs and as much memory as you can spare.
2. Close heavy apps, and plug the laptop in. Battery saver mode slows the CPU.
3. Start the stack in the background: `docker compose up --build -d`
4. Do not run `FLUSHDB` on Redis between tests. It deletes the rules table, and the apps only
   re-create the defaults when they restart.

## Run

```
# Main test: steps from 500 to 6000 requests per second, 20 seconds each
docker compose run --rm k6 run /benchmarks/step.js

# Other algorithms and your own steps
docker compose run --rm -e ALGO=sliding k6 run /benchmarks/step.js
docker compose run --rm -e ALGO=fixed -e RATES=1000,3000,5000 k6 run /benchmarks/step.js

# A longer hold at one rate (here 3 minutes at 20,000 requests per second)
docker compose run --rm -e RATES=20000 -e STEP_SECONDS=180 k6 run /benchmarks/step.js

# Few users, many requests each (makes sliding-window logs long)
docker compose run --rm -e RATES=5000 -e STEP_SECONDS=60 -e USERS=10 -e ALGO=sliding k6 run /benchmarks/step.js

# Raw ceiling (50 virtual users by default)
docker compose run --rm k6 run /benchmarks/max.js

# 1,000 simultaneous requests against a limit of 50
docker compose run --rm k6 run /benchmarks/burst.js
```

`ALGO` is `bucket` (default), `fixed` or `sliding`. Each script sets its own rules and puts the
defaults back when it finishes.

## How to read the results

- **step.js** prints one row per step. A step is healthy only when `actual/s` is close to `target/s`,
  `dropped` is 0 **and** `failed%` is 0. (`actual/s` counts every request sent, including failed ones.)
  Quote throughput together with the p99 from that same step.
- **burst.js**: the run fails if more (or fewer) than 50 requests got through. The token bucket may
  allow a few extra because it refills during the burst; that is expected. k6 also reports a high
  `http_req_failed` here, because it counts the correct HTTP 429 rejections as failures.

## How the measurements were taken

- Repeated runs: the 6,000 to 15,000 requests/s steps were run 3 times, the 15,000 to 30,000 steps 5 more
  times. Other results are single runs and are marked.
- Ranges, not best runs.
- Throughput and p99 always come from the same run and the same load level.

## Setup

| | |
|---|---|
| Machine | MacBook Pro 16-inch (Nov 2024), Apple M4 Max |
| CPU cores | 16 (all 16 available to Docker) |
| RAM | 64 GB (Docker's own memory limit showed about 7.7 GiB in `docker stats`) |
| OS | macOS Sequoia 15.7.5 |
| Docker | Docker Desktop 4.94.0, Engine 29.8.2, Compose v5.5.1 |
| Containers | Spring Boot 3.3.5 on Java 17 (x3), Redis 7.4, nginx 1.27, `grafana/k6:latest` |
| Other load | Normal desktop apps were open (IDE, browser, video calls); the machine was not isolated |
| Dates | 7 to 8 October 2026 |

## Results

### Step test: how latency grows with load

Token bucket on `/api/search`, 10,000 users, 20 seconds per step. The ranges cover the clean repeat runs
(the one anomalous run is described under "Unexpected observations").

| Load (req/s) | Runs | p50 ms | p99 ms (range) | Failed | Dropped |
|---|---|---|---|---|---|
| 6,000 | 3 | 0.4 | 1.3 to 1.5 | 0 | 0 |
| 8,000 | 3 | 0.4 | 1.5 to 1.9 | 0 | 0 |
| 10,000 | 3 | 0.4 | 1.8 to 2.4 | 0 | 0 |
| 12,000 | 3 | 0.5 | 2.4 to 4.0 | 0 | 0 |
| 15,000 | 8 | 0.5 | 2.8 to 4.2 | 0 | 0 |
| 20,000 | 5 | 0.5 to 0.6 | 4.0 to 5.3 | 0 | 0 |
| 25,000 | 4 | 0.6 | 4.6 to 7.9 | 0 | 0 |
| 30,000 | 3 | 0.7 | 8.4 to 11.1 | 0 | 0 |

p50 stayed under 1 ms at every load. p99 rises gradually with load. No clean run dropped or failed a
request at any step up to 30,000 requests/s, and the ceiling was not reached.

### Held load (soak)

| Load | Length | Algorithm | Requests | p50 ms | p95 ms | p99 ms | Slowest ms | Failed |
|---|---|---|---|---|---|---|---|---|
| 10,000/s | 5 min | bucket | 3,000,001 | 0.4 | 0.5 | 1.6 | 58.9 | 0 |
| 10,000/s | 5 min | sliding | 3,000,002 | 0.4 | 0.5 | 1.5 | 64.2 | 0 |
| 10,000/s | 5 min | fixed (run 1) | 3,000,001 | 0.4 | 0.5 | 1.8 | 26.3 | 0 |
| 10,000/s | 5 min | fixed (run 2) | 3,000,001 | 0.4 | 0.5 | 1.4 | 50.9 | 0 |
| 20,000/s | 3 min | fixed | 3,599,671 | 0.5 | 0.8 | 3.6 | 1,070.5 | 0 |
| 20,000/s | 3 min | bucket | 3,600,002 | 0.6 | 1.5 | 5.3 | 1,037.6 | 0 |
| 20,000/s | 3 min | sliding | 3,600,000 | 0.5 | 1.0 | 4.0 | 53.3 | 0 |

Single run each unless stated. Nothing was dropped in any of them.

### Algorithms compared

At 10,000 requests/s (5 minutes) and 20,000 requests/s (3 minutes) there is no consistent speed difference
between the three algorithms: the differences between algorithms are no larger than the run-to-run
variation (two token-bucket runs at identical settings gave p99 0.9 and 1.8 ms at 5,000 requests/s
over 10 users). At 20,000/s the token bucket had the highest p95 and p99 in its single run, but one run
per algorithm is not enough to call it slower.

The algorithms differ in memory and accuracy, not speed (next two sections).

### Burst: exactly the limit under concurrency

1,000 requests from 1,000 simultaneous virtual users, one shared user, limit 50, through nginx and three
app copies (`burst.js`):

| Algorithm | Sent | Allowed | Rejected |
|---|---|---|---|
| token bucket | 1,000 | 50 | 950 |
| fixed window | 1,000 | 50 | 950 |
| sliding window log | 1,000 | 50 | 950 |

The JUnit integration tests (`RateLimiterIntegrationTest`) repeat this and more (isolation between users, APIs and tiers;
recovery after the window; `Retry-After` headers). The token bucket may allow a few extra in burst runs because
it refills while the burst runs; the test accounts for this with a small slack. Fixed and sliding allow exactly
the limit.

### Memory per busy user in Redis

One user sending about 500 requests/s (10 users, 5,000 requests/s total), one key inspected with
`MEMORY USAGE`:

| Algorithm | Key size | Notes |
|---|---|---|
| token bucket | 104 bytes | two fields (`tokens`, `ts`), independent of request rate |
| sliding window log | 1,713,720 bytes (about 1.7 MB) | 15,088 log entries, sampled 30 s into a 60 s test; about 114 bytes per entry |

Single sample. The sliding log stores one entry per allowed request for the whole window, so its size is
bounded by the limit: at the benchmark's limit of 1,000,000 it can grow very large, while a rule of 100 per
minute holds at most 100 entries (about 11 KB, extrapolated at the same per-entry size).

### Redis timings

Redis's own counters, cumulative over all test runs up to that point: 4,558,517 rule lookups and exactly
4,558,517 Lua script calls, at an average of 0.42 microseconds per lookup and 9.74 microseconds per script
call. So every request that reached Redis ran one rule lookup and one atomic check, and Redis spent a tiny
amount of time on each.

## Unexpected observations

- **One anomalous run.** The first run of the 15,000 to 30,000 steps reported 60.9% failed requests overall
  (probably almost all at 25,000 and 30,000, judging by request counts), with p99 of 49 ms at 20,000.
  Five repeat runs and three 3-minute soaks afterwards were clean. The cause was not determined, and the
  error types were not captured, because the per-step failure columns were added to `step.js` afterwards.
- **Occasional one-second outliers.** Several runs had a slowest request of about 1.0 to 1.1 seconds
  (for example 1,052 ms at 30,000/s, 1,070 ms and 1,038 ms in two 20,000/s soaks), out of millions of
  requests, while p99 stayed in single-digit milliseconds. A one-second delay is typical of a dropped
  connection attempt being retried. That is an unconfirmed guess.
- **12 failed Lua calls** out of 4,558,517 in Redis's counters. Not investigated. A likely cause is the
  "script not loaded" reply that Spring answers by sending the script once, but that was not verified.
- p99 at 15,000/s was a little higher in the later runs (3.2 to 4.2 ms) than in the first three runs
  (2.8 to 3.1 ms). Probably machine noise; not investigated.

## What limited it

Not determined. Up to 30,000 requests/s nothing failed in the clean runs, so the true ceiling was not found.
One `docker stats` snapshot taken during a run at an unknown step showed k6 at 93% CPU, nginx 45%, each app
copy about 53% and Redis 30% (one core is 100%). That suggests none of the system's own components was near
its limit and that the load generator, which shares the machine, was the busiest, but it is a single
snapshot and does not prove it. Docker had all 16 of the machine's cores.

## Limits of these benchmarks

- Only the allowed path was measured (limit of 1,000,000 per minute); the rejection path was not benchmarked.
- Only `/api/search` as a Free user. `/api/export` and Premium traffic were not load tested.
- The load generator ran on the same machine as the system.
- nginx access logging was switched off for the runs.
- The fail-open path (Redis down) was not load tested.
- The memory comparison is a single sample.