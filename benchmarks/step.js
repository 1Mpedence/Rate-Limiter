// STEP TEST (the main benchmark).
// Sends a fixed number of requests per second, then raises it step by step, and
// reports latency AND errors at EACH step. That answers the real question: at what load
// does it start to struggle, and what is p99 at the load you quote?
//
//   docker compose run --rm k6 run /benchmarks/step.js
//   docker compose run --rm -e ALGO=sliding -e RATES=1000,3000,5000 k6 run /benchmarks/step.js
//
// Read the table: a step is only healthy if actual/s is close to target/s AND dropped is 0
// AND failed% is 0. A step with errors is not a success, however fast the errors came back.
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { call, setBenchRules, restoreDefaults } from './lib.js';

const rates = (__ENV.RATES || '500,1000,2000,3000,4000,5000,6000').split(',').map(Number);
const STEP_SECONDS = Number(__ENV.STEP_SECONDS || 20);
const GAP_SECONDS = 5;

// What kind of failure was it?
const err5xx = new Counter('resp_5xx');              // server-side error, e.g. 502 from nginx
const errConn = new Counter('resp_conn_error');      // no response at all (connection refused/reset/timeout)
const errOther = new Counter('resp_other_nonok');    // any other non-200 (e.g. 429, 4xx)

const scenarios = {};
const thresholds = { http_req_failed: ['rate<0.01'] };

rates.forEach((rate, i) => {
    scenarios[`rate_${rate}`] = {
        executor: 'constant-arrival-rate',   // "open model": requests keep arriving at this rate
        exec: 'hit',                          // even if the server slows down
        rate,
        timeUnit: '1s',
        duration: `${STEP_SECONDS}s`,
        startTime: `${i * (STEP_SECONDS + GAP_SECONDS)}s`,
        preAllocatedVUs: Math.max(50, Math.ceil(rate / 20)),
        maxVUs: Math.max(500, rate),
        tags: { rate: String(rate) },
    };
    // These thresholds never fail; they exist so k6 collects a line per step.
    thresholds[`http_req_duration{rate:${rate}}`] = ['p(99)>=0'];
    thresholds[`http_reqs{rate:${rate}}`] = ['count>=0'];
    thresholds[`dropped_iterations{rate:${rate}}`] = ['count>=0'];
    thresholds[`http_req_failed{rate:${rate}}`] = ['rate>=0'];
    thresholds[`resp_5xx{rate:${rate}}`] = ['count>=0'];
    thresholds[`resp_conn_error{rate:${rate}}`] = ['count>=0'];
    thresholds[`resp_other_nonok{rate:${rate}}`] = ['count>=0'];
});

export const options = {
    scenarios,
    thresholds,
    discardResponseBodies: true,
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
    setBenchRules();
}

export function hit() {
    const res = call('search');
    check(res, { 'status is 200': (r) => r.status === 200 });
    if (res.status === 0) {
        errConn.add(1);
    } else if (res.status >= 500) {
        err5xx.add(1);
    } else if (res.status !== 200) {
        errOther.add(1);
    }
}

export function teardown() {
    restoreDefaults();
}

function pad(value, width) {
    return String(value).padStart(width);
}

function count(data, name) {
    const m = data.metrics[name];
    return m ? m.values.count : 0;
}

// k6's /s figures are averaged over the WHOLE run (all steps plus the gaps between them),
// which makes them look too low. So we print our own table: requests in a step / step length.
// "actual/s" counts every request that was sent, including ones that failed.
export function handleSummary(data) {
    const lines = [
        `Algorithm: ${__ENV.ALGO || 'bucket'}   distinct users: ${__ENV.USERS || 10000}   step length: ${STEP_SECONDS}s`,
        'target/s  actual/s  dropped  failed%     5xx  no-resp   other   p50 ms   p95 ms   p99 ms   max ms',
    ];
    rates.forEach((rate) => {
        const dur = data.metrics[`http_req_duration{rate:${rate}}`];
        const failed = data.metrics[`http_req_failed{rate:${rate}}`];
        const v = dur ? dur.values : {};
        const failedPct = failed ? failed.values.rate * 100 : 0;
        lines.push([
            pad(rate, 8),
            pad((count(data, `http_reqs{rate:${rate}}`) / STEP_SECONDS).toFixed(0), 9),
            pad(count(data, `dropped_iterations{rate:${rate}}`), 8),
            pad(failedPct.toFixed(1), 8),
            pad(count(data, `resp_5xx{rate:${rate}}`), 7),
            pad(count(data, `resp_conn_error{rate:${rate}}`), 8),
            pad(count(data, `resp_other_nonok{rate:${rate}}`), 7),
            pad((v.med || 0).toFixed(1), 8),
            pad((v['p(95)'] || 0).toFixed(1), 8),
            pad((v['p(99)'] || 0).toFixed(1), 8),
            pad((v.max || 0).toFixed(1), 8),
        ].join(' '));
    });
    const overall = data.metrics.http_req_failed ? data.metrics.http_req_failed.values.rate : 0;
    lines.push('');
    lines.push(`Failed requests overall (anything other than 200): ${(overall * 100).toFixed(2)}%`);
    lines.push('A step is healthy only when actual/s is close to target/s, dropped is 0 AND failed% is 0.');
    lines.push('5xx = error replies (e.g. 502 from nginx); no-resp = connection refused, reset or timed out.');
    return { stdout: lines.join('\n') + '\n' };
}