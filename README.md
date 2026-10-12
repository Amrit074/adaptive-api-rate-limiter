# Sentinel — API Traffic Control

A Spring Boot and Redis project with a responsive dark dashboard, per-client API keys, and atomic burst plus sustained quotas.

## New workspace

- **Request playground:** send one request or a 20-request burst; inspect actual HTTP decisions, remaining quota, retry time, session p95, and the latest activity.
- **Client policies:** create clients, edit limits, disable access, and rotate keys. Only SHA-256 hashes are stored; a new key is returned once.
- **Atomic dual windows:** both Redis sorted sets must have room before either receives a permit. Rotation preserves quota. Window durations are fixed after creation because trimmed history cannot be reconstructed.
- **Protected administration:** configure `RATE_LIMITER_ADMIN_KEY` (at least 32 random characters), then unlock the dashboard. Admin/operational routes require `X-Admin-Key`; missing server configuration fails with 503.
- **Failure behavior:** Redis enforcement fails open with an explicit outcome; SQL client authentication fails closed. Anonymous identity uses the network peer, so deployments behind proxies need a trusted-proxy strategy.

The dashboard statistics are browser-session observations, capped at 1,000 results, not fleet-wide analytics. This app protects its own demo API; it is not a general reverse proxy. The existing optional anomaly-detection worker remains available.

## Run and verify

Provide MySQL and Redis settings from `.env.example`, set a strong admin secret, and run `mvn spring-boot:run`. Visit `http://localhost:8080`. The frontend is bundled with the backend; no Node build is required. Run `mvn test` with Docker available for real Redis integration tests.

The disposable stack under `benchmarks/compose.yml` is useful for local experiments after `mvn package`; its MySQL data is temporary. Do not use its test credentials or storage configuration in production.

See [the beginner engineering handbook](docs/PROJECT_HANDBOOK.md) for diagrams, algorithms, worked examples, failure behavior, and interview exercises covering all three projects. Historical benchmark numbers below refer to the anonymous service path, not the new authenticated policy path.

[Download the illustrated PDF handbook](docs/Project_Engineering_Handbook.pdf).

---

## Earlier architecture and benchmark notes

# Adaptive API Rate Limiter with ML Anomaly Detection

A deployable Spring Boot API gateway demo that combines:

- Redis Sorted Set sliding-window rate limiting
- JSON `429 Too Many Requests` responses with rate-limit headers
- MySQL traffic logging for request analysis
- Python Isolation Forest anomaly detection sidecar
- Redis TTL blocklist for ML-flagged abusive IPs
- Built-in frontend dashboard served from the same Spring Boot app

## Live Demo Flow

Open the app root:

```text
http://localhost:8080/
```

Click **Send burst**. The UI calls the protected endpoint `/api/test` repeatedly.

Expected result:

1. First requests return `200 OK`.
2. Remaining request count falls to `0`.
3. Extra requests return JSON `429 Too Many Requests`.
4. MySQL traffic logs show endpoint, status code, latency, timestamp, and request count.

## Architecture

```text
Client / Frontend
        |
        v
Spring Boot OncePerRequestFilter
        |
        +--> Redis TTL blocklist check
        |
        +--> Redis Sorted Set sliding-window limiter
        |
        +--> Controller if request is allowed
        |
        +--> Async MySQL traffic logging

Python ML Sidecar
        |
        +--> Reads MySQL traffic logs
        +--> Runs Isolation Forest anomaly detection
        +--> Writes anomaly_result rows

AnomalySyncService
        |
        +--> Syncs anomalous IPs into Redis TTL blocklist
```

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java 17, Spring Boot 3.2 |
| Request interception | `OncePerRequestFilter` |
| Rate limiting | Redis Sorted Sets |
| Temporary blocklist | Redis String keys with TTL |
| Persistent logs | MySQL, Spring Data JPA |
| ML anomaly detection | Python, scikit-learn Isolation Forest |
| API docs | Swagger/OpenAPI |
| Deployment | Docker, Docker Compose, Render |

## API Endpoints

| Method | Endpoint | Rate Limited | Description |
|---|---|---:|---|
| `GET` | `/` | No | Frontend dashboard |
| `GET` | `/api/test` | Yes | Demo endpoint for triggering the limiter |
| `GET` | `/api/hello` | Yes | Alternate protected demo endpoint |
| `GET` | `/api/demo/client-ip` | No | Shows detected requester IP and remaining requests |
| `GET` | `/api/demo/config` | No | Shows active rate-limit settings |
| `GET` | `/api/metrics/summary` | No | Request, block, and anomaly counts |
| `GET` | `/api/metrics/recent` | No | Recent MySQL traffic log rows |
| `GET` | `/api/metrics/top-ips` | No | Top IPs by request volume |
| `GET` | `/api/metrics/anomalies` | No | Recent ML anomaly rows |
| `POST` | `/api/admin/block/{ip}` | No | Manually block an IP |
| `DELETE` | `/api/admin/unblock/{ip}` | No | Manually unblock an IP |
| `GET` | `/api/health/rate-limit/{ip}` | No | Inspect an IP's current count |

Swagger UI:

```text
http://localhost:8080/swagger-ui.html
```

## Rate-Limit Headers

Every protected endpoint response includes:

```text
X-RateLimit-Limit: 5
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 60
X-RateLimit-Window: 60s
X-RateLimit-Type: RATE_LIMITED
Retry-After: 60
```

Example `429` body:

```json
{
  "status": 429,
  "error": "Too Many Requests",
  "message": "Rate limit exceeded. Please try again later.",
  "blockType": "RATE_LIMITED",
  "reason": "Rate limit exceeded: 5/5 requests in 60s",
  "limit": 5,
  "remainingRequests": 0,
  "retryAfterSeconds": 60
}
```

## Configuration

Defaults are intentionally demo-friendly:

```yaml
rate-limiter:
  max-requests: 5
  window-seconds: 60
  block-ttl-seconds: 300
```

Override in deployment:

```text
RATE_LIMITER_MAX_REQUESTS=100
RATE_LIMITER_WINDOW_SECONDS=60
RATE_LIMITER_BLOCK_TTL_SECONDS=300
```

## Run Locally

Start dependencies:

```bash
docker-compose up mysql redis -d
```

Start Spring Boot:

```bash
mvn spring-boot:run
```

Start the ML sidecar:

```bash
cd ml-service
pip install -r requirements.txt
python anomaly_detector.py
```

## Docker Compose

```bash
docker-compose up --build
```

Then open:

```text
http://localhost:8080/
```

## Interview Demo Commands

PowerShell:

```powershell
1..10 | ForEach-Object { curl.exe -i http://localhost:8080/api/test; echo "" }
```

Bash:

```bash
for i in {1..10}; do curl -i http://localhost:8080/api/test; echo; done
```

Redis proof:

```bash
redis-cli KEYS "rl:*"
redis-cli ZRANGE rl:<ip> 0 -1 WITHSCORES
redis-cli TTL block:<ip>
```

MySQL proof:

```sql
SELECT client_ip, endpoint, status_code, latency_ms, request_count, timestamp
FROM traffic_log
ORDER BY timestamp DESC
LIMIT 10;
```

## Deployment Notes

For Render/Aiven deployment, set these environment variables:

```text
SPRING_DATASOURCE_URL
SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_PASSWORD
SPRING_DATA_REDIS_HOST
SPRING_DATA_REDIS_PORT
SPRING_DATA_REDIS_USERNAME
SPRING_DATA_REDIS_PASSWORD
SPRING_DATA_REDIS_SSL_ENABLED=true
```

The app uses fail-open behavior if Redis becomes unavailable: protected API requests are allowed and a warning is logged. This prevents Redis outages from taking down the whole demo/API path. For a security-critical production API, this policy can be changed to fail-closed.

## What To Show In Interviews

- Frontend burst test producing a `429` response
- Response headers showing limit, remaining, reset, and retry-after
- MySQL traffic log rows after the burst
- Redis Sorted Set keys for sliding-window request history
- Redis TTL blocklist for anomaly/manual blocking
- Architecture diagram in this README

## Atomic Redis Sliding Window

The sliding-window decision is performed by one Redis Lua script through Spring's
`RedisTemplate.execute(DefaultRedisScript, ...)`. The script removes expired
sorted-set entries, counts the remaining requests, rejects or inserts the current
request, refreshes the TTL for allowed requests, and returns the request count and
precise retry time.

### Why the previous flow could exceed the limit

Previously, cleanup, `ZCARD`, limit comparison, `ZADD`, and `EXPIRE` were separate
commands. Two requests could both read a count of `4` with a limit of `5`, both
decide they were allowed, and both insert entries. The resulting count becomes `6`:
the limit was bypassed even though each individual Redis command was safe.

Lua fixes the check-then-act gap. Redis executes a script to completion without
interleaving commands from other clients, so a concurrent caller can observe the
state only before or after the entire decision. Scripts must remain short and
non-blocking because they execute on Redis's command-processing thread.

### Concurrency proof

`RateLimiterRedisConcurrencyTest` starts a real Redis 7 container and releases 100
requests for one IP concurrently against a limit of 10. It asserts that exactly 10
are allowed and that the stored sliding-window count is 10. Docker is required;
CI must not silently skip the concurrency proof. Run it with:

```bash
mvn test
```

## Verification and performance evidence (11 October 2026)

Run `mvn -B verify` with Java 17+ and Docker. The GitHub Actions workflow runs
the same command on pushes and pull requests and uploads JUnit reports. It has
been configured locally; a hosted Actions run requires pushing this code.

Tests cover 100 simultaneous callers, window/key expiration, blocklist expiry,
Redis command delays, server shutdown/restart, fail-open recovery, and retry of
failed MySQL-to-Redis anomaly synchronization. Faults target disposable Redis
containers only. Redis recovery restores enforcement; calls admitted during an
outage are not retroactively counted. Fail-open is an availability tradeoff.

Service benchmark: `mvn -B test -Dtest=RateLimiterBenchmarkTest -Dbenchmark=true`.
The captured run used Windows 11, Java 21.0.1, Redis 7 in Docker, 10 workers,
1,000 warmup calls, and 10,000 calls per workload. Raw CSV and environment details
are in `benchmarks/results/`. A single local run is not a capacity guarantee.

| Workload | Allowed | Rejected | Fail-open | Decisions/sec | p50 ms | p95 ms | p99 ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| Unique clients | 10,000 | 0 | 0 | 3,694.65 | 2.478 | 4.457 | 6.263 |
| Shared client | 100 | 9,900 | 0 | 5,927.36 | 1.541 | 2.512 | 3.459 |

These are service calls, excluding HTTP, SQL logging, ML processing, and filter
metrics. Rejected requests are cheaper; do not describe their throughput as
successful API throughput. No unsafe non-atomic baseline is exposed by the app.

For full HTTP load testing with k6, see `benchmarks/README.md`.

### Metrics

Actuator `/actuator/metrics` exposes `rate_limiter.decisions` and
`rate_limiter.decision`, tagged with the four fixed outcomes, plus
`rate_limiter.redis_errors`. The timer measures the limiter decision, not total
HTTP latency. Spring's `http.server.requests` covers HTTP timing. Do not label
metrics with client IPs. Prometheus export requires a registry dependency; the
current build uses Actuator's JSON metrics. Keep operational endpoints behind
appropriate access controls in a deployment.

### Current limitations

This is a portfolio demo. Admin block/unblock endpoints are unauthenticated and
forwarded IP headers are trusted; use only behind a trusted, sanitizing proxy and
restrict admin routes before any public production use. Lua atomicity applies to
the rolling-window operation; the preceding blocklist read is separate. The ML
sidecar runs every 60 seconds, while `AnomalySyncService` polls every 30 seconds.

### Interview-ready explanation

“A sliding-window limiter has a check-then-act race when cleanup, count, decision,
and insert are separate Redis calls. Under a burst, multiple requests can read the
same count below the threshold and all insert. I moved that state transition into a
short Redis Lua script executed through `DefaultRedisScript`, which Redis runs
serially to completion. The service still uses the same sorted-set window, TTL
blocklist, filter headers, asynchronous MySQL logging, and Isolation Forest flow;
only the rate-limit decision became atomic. I also added a real-Redis concurrent
test that releases 100 requests at once and proves a limit of 10 cannot be
exceeded.”

### Resume bullets

- Hardened a Spring Boot Redis sorted-set sliding-window rate limiter with an
  atomic Lua state transition, eliminating concurrent check-then-act bypasses.
- Integrated `RedisTemplate` and `DefaultRedisScript` to return rate-limit counts
  and precise retry intervals while preserving `X-RateLimit-*` and `Retry-After`
  responses.
- Added a Testcontainers Redis concurrency test that simultaneously submits 100
  requests and verifies the configured limit is enforced exactly; retained Redis
  TTL anomaly blocklisting, asynchronous MySQL telemetry, and Isolation Forest
  detection pipeline.
