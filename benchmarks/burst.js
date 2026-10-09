// BURST TEST: correctness under pressure.
// 1000 virtual users each send ONE request at the same moment, all as the same user,
// against a limit of 50. Unlike the throughput tests this DOES test the limit: exactly
// 50 requests may get through (the token bucket may allow a few extra because it keeps
// refilling while the burst runs).
//
//   docker compose run --rm k6 run /benchmarks/burst.js
//   docker compose run --rm -e ALGO=sliding k6 run /benchmarks/burst.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE, ALGO, setRule, restoreDefaults } from './lib.js';

const LIMIT = 50;
const allowed = new Counter('allowed_total');
const rejected = new Counter('rejected_total');

export const options = {
  scenarios: {
    burst: { executor: 'shared-iterations', vus: 1000, iterations: 1000, maxDuration: '60s' },
  },
  thresholds: {
    // fails the run if too many (or too few) requests got through
    allowed_total: [`count>=${LIMIT}`, `count<=${ALGO === 'bucket' ? LIMIT + 5 : LIMIT}`],
    checks: ['rate==1'],
  },
};

export function setup() {
  // 10-minute window so a fixed window is very unlikely to roll over mid-burst.
  setRule('FREE', 'search', ALGO, LIMIT, 600000);
  return { user: `burst-${Date.now()}` };
}

export default function (data) {
  const res = http.get(`${BASE}/api/search`, {
    headers: { 'X-User-Id': data.user, 'X-Tier': 'FREE' },
  });
  if (res.status === 200) allowed.add(1);
  if (res.status === 429) rejected.add(1);
  check(res, { 'status is 200 or 429': (r) => r.status === 200 || r.status === 429 });
}

export function teardown() {
  restoreDefaults();
}
