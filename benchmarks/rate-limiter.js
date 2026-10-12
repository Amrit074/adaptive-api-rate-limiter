import http from 'k6/http';
import { Counter, Rate, Trend } from 'k6/metrics';
import { check, sleep, fail } from 'k6';

const allowed = new Counter('allowed_requests');
const rejected = new Counter('rejected_requests');
const failedOpen = new Rate('fail_open_rate');
const unexpected = new Rate('unexpected_response_rate');
const latency = new Trend('api_latency_ms', true);
const requests = new Counter('api_requests');
export const options = {
  vus: Number(__ENV.VUS || 10), duration: __ENV.DURATION || '30s',
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: { unexpected_response_rate: ['rate==0'], fail_open_rate: ['rate==0'], checks: ['rate==1'] },
};
http.setResponseCallback(http.expectedStatuses(200, 429));
export function setup() {
  for (let attempt = 0; attempt < 30; attempt++) {
    const response = http.get(`${__ENV.BASE_URL || 'http://localhost:8080'}/actuator/health`,
      { tags: { phase: 'readiness' } });
    if (response.status === 200) return;
    sleep(1);
  }
  fail('Benchmark API did not become healthy; no load measurements collected');
}
export default function () {
  // Synthetic identities are only for this isolated demo, whose proxy headers are trusted.
  const r = http.get(`${__ENV.BASE_URL || 'http://localhost:8080'}/api/test`, {
    headers: __ENV.CLIENT_IP ? { 'X-Forwarded-For': __ENV.CLIENT_IP } : {},
  });
  requests.add(1);
  latency.add(r.timings.duration);
  if (r.status === 200) allowed.add(1);
  if (r.status === 429) rejected.add(1);
  failedOpen.add(r.headers['X-Ratelimit-Type'] === 'FAIL_OPEN');
  unexpected.add(r.status !== 200 && r.status !== 429);
  check(r, { 'has limiter decision': (v) => !!v.headers['X-Ratelimit-Type'] });
}
export function handleSummary(data) {
  return { [__ENV.RESULT_FILE || 'rate-limiter-results.json']: JSON.stringify({
    conditions: { vus: options.vus, duration: options.duration, target: __ENV.BASE_URL || 'localhost:8080',
      clientIp: __ENV.CLIENT_IP || 'container source IP',
      workload: 'single source IP, shared rolling-window quota; includes allowed and rejected responses' },
    ...data,
  }, null, 2) };
}
