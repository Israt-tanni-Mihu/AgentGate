// QA2: the gateway adds <= 30 ms at p95 at 100 calls/s (no approval waits).
//
// One script, two modes (env MODE):
//   baseline : GET mock-tools directly              (what the tool costs without the gateway)
//   gateway  : POST /api/agent/calls/getCustomer    (the same tool call through the full pipeline)
// Overhead = p95(gateway) - p95(baseline). The runner (scripts/qa2-performance.ps1) passes the baseline p95
// to the gateway run as BASELINE_P95_MS, so k6 itself fails the run when the overhead is above MAX_OVERHEAD_MS.
//
// The call is LOW risk and allowed by policy -> it never waits for a human (QA2 excludes approval waits).
import http from 'k6/http';
import { check, fail } from 'k6';
import encoding from 'k6/encoding';

const MODE = __ENV.MODE || 'gateway';
const GATEWAY = __ENV.GATEWAY_URL || 'http://localhost:8080';
const MOCK_TOOLS = __ENV.MOCK_TOOLS_URL || 'http://localhost:8081';
const RATE = Number(__ENV.RATE || 100);
const DURATION = __ENV.DURATION || '60s';
const WARMUP = __ENV.WARMUP || '15s';
const MAX_OVERHEAD_MS = Number(__ENV.MAX_OVERHEAD_MS || 30);
const BASELINE_P95_MS = Number(__ENV.BASELINE_P95_MS || 0);
const AGENT_ID = __ENV.AGENT_ID || 'support-bot';
const AGENT_SECRET = __ENV.AGENT_SECRET || 'support-bot-local-secret';
const CUSTOMER = 1;

// Same load shape for both modes. constant-arrival-rate starts 100 requests/s no matter how slow the answers are.
const scenario = (startTime, duration) => ({
  executor: 'constant-arrival-rate',
  rate: RATE, timeUnit: '1s', duration,
  preAllocatedVUs: 50, maxVUs: 400,
  startTime,
});

export const options = {
  scenarios: {
    warmup: scenario('0s', WARMUP),               // JIT + connection pools; NOT judged
    measure: scenario(WARMUP, DURATION),
  },
  thresholds: {
    // Gateway mode: p95 must stay below baseline p95 + 30 ms. Baseline mode: only "no errors".
    'http_req_duration{scenario:measure}': MODE === 'gateway'
      ? [`p(95)<${BASELINE_P95_MS + MAX_OVERHEAD_MS}`] : ['p(95)<1000'],
    'http_req_failed{scenario:measure}': ['rate==0'],
    'checks{scenario:measure}': ['rate==1'],
    // If k6 could not keep 100/s, the numbers would be wrong, so that is a failure too.
    'dropped_iterations{scenario:measure}': ['count==0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// Runs once: a real OAuth2 client-credentials token (valid 5 min; warm-up + test = 75 s).
export function setup() {
  if (MODE === 'baseline') return {};
  const res = http.post(`${GATEWAY}/oauth2/token`, { grant_type: 'client_credentials' },
    { headers: { Authorization: 'Basic ' + encoding.b64encode(`${AGENT_ID}:${AGENT_SECRET}`) } });
  if (res.status !== 200) fail(`token request failed: HTTP ${res.status} ${res.body}`);
  return { token: res.json('access_token') };
}

export default function (data) {
  let res;
  if (MODE === 'baseline') {
    res = http.get(`${MOCK_TOOLS}/api/customers/${CUSTOMER}`);
  } else {
    res = http.post(`${GATEWAY}/api/agent/calls/getCustomer`, JSON.stringify({ id: CUSTOMER }), {
      headers: {
        Authorization: `Bearer ${data.token}`,
        'X-On-Behalf-Of': String(CUSTOMER),
        'Content-Type': 'application/json',
      },
    });
  }
  check(res, { 'status is 200': (r) => r.status === 200 });
}
