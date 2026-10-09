// MAX THROUGHPUT TEST.
// A fixed crowd of virtual users sends requests back to back as fast as the system
// answers. This finds the raw ceiling in requests per second. Latency here is measured
// while the system is saturated, so do NOT quote this p99 as "normal" latency; quote the
// p99 from the step test at the load you claim.
//
//   docker compose run --rm k6 run /benchmarks/max.js
//   docker compose run --rm -e VUS=100 -e ALGO=fixed k6 run /benchmarks/max.js
//
// Look at the lines ending {phase:measure}: only the steady part is counted, the warm-up
// is excluded.
import { check } from 'k6';
import { call, setBenchRules, restoreDefaults } from './lib.js';

const VUS = Number(__ENV.VUS || 50);

export const options = {
  scenarios: {
    warmup: {
      executor: 'constant-vus', vus: VUS, duration: '10s',
      tags: { phase: 'warmup' },
    },
    measure: {
      executor: 'constant-vus', vus: VUS, duration: '30s', startTime: '15s',
      tags: { phase: 'measure' },
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_reqs{phase:measure}': ['count>=0'],
    'http_req_duration{phase:measure}': ['p(99)>=0'],
  },
  discardResponseBodies: true,
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  setBenchRules();
}

export default function () {
  const res = call('search');
  check(res, { 'status is 200': (r) => r.status === 200 });
}

export function teardown() {
  restoreDefaults();
}

function pad(value, width) {
  return String(value).padStart(width);
}

// k6's /s figure would divide the measured requests by the whole run (warm-up included),
// so we compute requests per second over the 30 steady seconds ourselves.
export function handleSummary(data) {
  const MEASURE_SECONDS = 30;
  const reqs = data.metrics['http_reqs{phase:measure}'];
  const dur = data.metrics['http_req_duration{phase:measure}'];
  const count = reqs ? reqs.values.count : 0;
  const v = dur ? dur.values : {};
  const failed = data.metrics.http_req_failed ? data.metrics.http_req_failed.values.rate : 0;
  const lines = [
    `Algorithm: ${__ENV.ALGO || 'bucket'}   virtual users: ${VUS}   steady period: ${MEASURE_SECONDS}s`,
    `Requests per second: ${(count / MEASURE_SECONDS).toFixed(0)}   (${count} requests)`,
    `Latency ms:  p50 ${pad((v.med || 0).toFixed(1), 6)}   p95 ${pad((v['p(95)'] || 0).toFixed(1), 6)}   ` +
      `p99 ${pad((v['p(99)'] || 0).toFixed(1), 6)}   max ${pad((v.max || 0).toFixed(1), 6)}`,
    `Failed requests (anything other than 200): ${(failed * 100).toFixed(2)}%`,
    'This p99 is measured while saturated. Quote latency from the step test instead.',
  ];
  return { stdout: lines.join('\n') + '\n' };
}
