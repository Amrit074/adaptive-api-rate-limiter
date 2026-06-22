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
are allowed and that the stored sliding-window count is 10. The test is skipped
automatically when Docker is unavailable; run it with:

```bash
mvn test
```

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
