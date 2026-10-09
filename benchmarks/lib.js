// Shared helpers for the k6 load tests.
import http from 'k6/http';

// Inside the Docker network nginx listens on port 80 under the name "nginx".
export const BASE = __ENV.BASE_URL || 'http://nginx';

// Which algorithm the benchmark rules use: bucket | fixed | sliding
export const ALGO = __ENV.ALGO || 'bucket';

// How many different users send traffic. Many users = many different Redis keys,
// which is closer to real traffic than one user hammering a single key.
export const USERS = Number(__ENV.USERS || 10000);

const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };

export function setRule(tier, api, algorithm, limit, windowMs) {
  const res = http.put(
    `${BASE}/admin/rules/${tier}/${api}`,
    JSON.stringify({ algorithm, limit, windowMs }),
    JSON_HEADERS,
  );
  if (res.status !== 200) {
    throw new Error(`Could not set rule ${tier}/${api}: HTTP ${res.status} ${res.body}`);
  }
}

// Throughput tests must measure how fast the SYSTEM is, not how fast it can say "no".
// So the limit is set so high that no request is ever rejected.
export function setBenchRules() {
  setRule('FREE', 'search', ALGO, 1000000, 60000);
}

// Put the default rules back (same values as RuleService).
export function restoreDefaults() {
  setRule('FREE', 'search', 'bucket', 10, 60000);
  setRule('PREMIUM', 'search', 'bucket', 100, 60000);
  setRule('FREE', 'export', 'sliding', 2, 60000);
  setRule('PREMIUM', 'export', 'sliding', 20, 60000);
}

// One request as a random one of the USERS users.
export function call(api) {
  const user = `u${Math.floor(Math.random() * USERS)}`;
  return http.get(`${BASE}/api/${api || 'search'}`, {
    headers: { 'X-User-Id': user, 'X-Tier': 'FREE' },
  });
}
