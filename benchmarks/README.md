# Repeatable benchmarks

## Captured HTTP results - 11 October 2026

Windows 11 host; Docker Desktop with approximately 3.7 GiB assigned memory;
Java 17.0.20.1 application, Redis 7, MySQL 8.0 and k6 0.54.0. Each run used
30 seconds of closed-loop traffic, quota 100 per 60 seconds and one shared
identity. There was no explicit HTTP warmup; later runs benefited from JVM warmup
and retained SQL history. These are exploratory local measurements, not a
controlled hardware scaling study. ML inference was not enabled.

| Users | Responses/sec* | Allowed | Rejected | p50 ms | p95 ms | p99 ms |
|---|---:|---:|---:|---:|---:|---:|
| 1 | 444.25 | 100 | 13,816 | 1.714 | 3.293 | 4.977 |
| 10 | 2,359.70 | 100 | 71,264 | 3.196 | 8.376 | 14.734 |
| 25 | 5,342.45 | 100 | 161,390 | 3.426 | 10.349 | 18.221 |

All three final runs had zero unexpected responses, zero fail-open outcomes,
and passed the limiter-header check. Raw JSON is in `results/http-*.json`.
*Reported k6 counter rate includes setup overhead. Latency uses only API samples.
Most responses are rejections: these numbers do not establish allowed-request
capacity. The service-only allowed benchmark is documented separately.

## Reproduce

Use only the isolated local stack. Prerequisites: Java 17+, Maven, Docker Compose.
From the project root:

```sh
mvn -B verify
docker compose -p portfolio-benchmark -f benchmarks/compose.yml up -d app
```

Wait for `http://localhost:18080/actuator/health` to return UP. Then:

```sh
docker compose -p portfolio-benchmark -f benchmarks/compose.yml run --rm -e VUS=1 -e RESULT_FILE=http-1vu.json k6
docker compose -p portfolio-benchmark -f benchmarks/compose.yml run --rm -e VUS=10 -e CLIENT_IP=198.18.0.10 -e RESULT_FILE=http-10vu.json k6
docker compose -p portfolio-benchmark -f benchmarks/compose.yml run --rm -e VUS=25 -e CLIENT_IP=198.18.0.25 -e RESULT_FILE=http-25vu.json k6
docker compose -p portfolio-benchmark -f benchmarks/compose.yml down
```

For repeated runs of the same identity, allow 60 seconds so its quota resets.
The 10/25-user runs use distinct synthetic forwarded IPs in this isolated demo,
so they do not consume each other's quota. Each run lasts
30 seconds. The default quota is 100 requests per 60 seconds for a single source
IP. Expect mostly 429s, counted separately from allowed requests. Fail-open and
unexpected HTTP responses fail thresholds. The JSON records VUs, duration,
request throughput, p50/p95/p99, counts, and failures. Use `api_latency_ms` for
request latency (it excludes readiness probes). The counter's reported rate uses
k6's total run duration, including readiness/setup overhead; counts are exact.
The disposable stack uses
real Redis/MySQL, HTTP, the request filter, and async traffic logging; the ML
sidecar is intentionally excluded. Docker CPU/memory allocation affects results.

To measure mostly allowed traffic, recreate the app with `BENCH_LIMIT=1000000`
in the environment, then rerun at the same VU levels. Record that changed quota;
do not compare a rejected-heavy run against an allowed-heavy run as equivalents.
Do not run two load generators at the same time. Keep raw outputs and report
environment, quota, warmup, duration, and rejection percentage with any numbers.

`RateLimiterBenchmarkTest` is a separate finite service-level benchmark. Run
`mvn -B test -Dtest=RateLimiterBenchmarkTest -Dbenchmark=true` to regenerate
`target/benchmarks/service.csv` and `environment.txt`. It uses real Redis without
HTTP or SQL and is not a replacement for the HTTP measurements.
