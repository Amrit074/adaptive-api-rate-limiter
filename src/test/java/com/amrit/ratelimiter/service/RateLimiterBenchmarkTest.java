package com.amrit.ratelimiter.service;

import com.amrit.ratelimiter.config.RedisConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in service benchmark, not HTTP throughput or a production capacity claim. */
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
class RateLimiterBenchmarkTest {
    @Test void measureRealRedisDecisions() throws Exception {
        try (var redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379)) {
            redis.start();
            var factory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
            factory.afterPropertiesSet();
            var pool = Executors.newFixedThreadPool(10);
            try {
                var template = new StringRedisTemplate(factory);
                var limiter = new RateLimiterService(template, template, new RedisConfig().slidingWindowRateLimitScript());
                ReflectionTestUtils.setField(limiter, "maxRequests", 100);
                ReflectionTestUtils.setField(limiter, "windowSeconds", 60);
                for (int i = 0; i < 1000; i++) limiter.checkRateLimit("warmup");
                var output = new StringBuilder("workload,workers,requests,allowed,rejected,fail_open,throughput_rps,p50_ms,p95_ms,p99_ms\n");
                for (String workload : List.of("unique-client", "shared-client")) {
                    int count = 10000;
                    long[] times = new long[count];
                    var types = new RateLimiterService.RateLimitResult.ResultType[count];
                    var tasks = new ArrayList<Callable<Void>>();
                    for (int worker = 0; worker < 10; worker++) {
                        final int offset = worker;
                        tasks.add(() -> {
                            for (int i = offset; i < count; i += 10) {
                                long start = System.nanoTime();
                                var result = limiter.checkRateLimit(workload.equals("unique-client") ? "bench-" + i : "shared");
                                times[i] = System.nanoTime() - start;
                                types[i] = result.type();
                            }
                            return null;
                        });
                    }
                    long start = System.nanoTime();
                    for (var future : pool.invokeAll(tasks)) future.get();
                    double seconds = (System.nanoTime() - start) / 1e9;
                    Arrays.sort(times);
                    long allowed = Arrays.stream(types).filter(t -> t.name().equals("ALLOWED")).count();
                    long rejected = Arrays.stream(types).filter(t -> t.name().equals("RATE_LIMITED")).count();
                    long failOpen = Arrays.stream(types).filter(t -> t.name().equals("FAIL_OPEN")).count();
                    assertThat(failOpen).isZero();
                    assertThat(allowed + rejected).isEqualTo(count);
                    output.append(String.format(Locale.ROOT, "%s,10,%d,%d,%d,%d,%.2f,%.3f,%.3f,%.3f%n",
                        workload, count, allowed, rejected, failOpen, count / seconds,
                        times[4999] / 1e6, times[9499] / 1e6, times[9899] / 1e6));
                }
                Files.createDirectories(Path.of("target/benchmarks"));
                Files.writeString(Path.of("target/benchmarks/service.csv"), output);
                Files.writeString(Path.of("target/benchmarks/environment.txt"),
                    "timestamp=" + java.time.Instant.now() + "\njava=" + System.getProperty("java.version") +
                    "\nos=" + System.getProperty("os.name") + "\nprocessors=" + Runtime.getRuntime().availableProcessors() +
                    "\nredis=redis:7-alpine\nwarmup=1000 calls\nlimit=100 per 60s\nworkers=10\n" +
                    "scope=service calls, Redis container over localhost; excludes HTTP, MySQL logging, ML and metrics\n");
            } finally { pool.shutdownNow(); factory.destroy(); }
        }
    }
}
