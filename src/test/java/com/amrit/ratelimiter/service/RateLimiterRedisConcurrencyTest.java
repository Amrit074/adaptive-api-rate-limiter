package com.amrit.ratelimiter.service;

import com.amrit.ratelimiter.config.RedisConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the actual Lua script against Redis. Docker is required so that
 * CI cannot silently pass without running the concurrency proof.
 */
@Testcontainers
class RateLimiterRedisConcurrencyTest {

    private static final int LIMIT = 10;

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private RedisTemplate<String, String> redisTemplate;
    private RateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        rateLimiterService = new RateLimiterService(
            redisTemplate,
            redisTemplate,
            new RedisConfig().slidingWindowRateLimitScript()
        );
        ReflectionTestUtils.setField(rateLimiterService, "maxRequests", LIMIT);
        ReflectionTestUtils.setField(rateLimiterService, "windowSeconds", 60);
        ReflectionTestUtils.setField(rateLimiterService, "blockTtlSeconds", 300);
        redisTemplate.delete("rl:concurrency-test");
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void concurrentRequestsCannotBypassTheConfiguredLimit() throws Exception {
        int attempts = 100;
        // Every task must reach the gate before it opens; fewer workers would
        // queue tasks behind threads already waiting at the gate.
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<RateLimiterService.RateLimitResult> results = new ArrayList<>();

        for (int i = 0; i < attempts; i++) {
            pool.submit(() -> {
                ready.countDown();
                start.await();
                RateLimiterService.RateLimitResult result = rateLimiterService.checkRateLimit("concurrency-test");
                synchronized (results) {
                    results.add(result);
                }
                return null;
            });
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        long allowed = results.stream().filter(RateLimiterService.RateLimitResult::allowed).count();
        assertThat(allowed).isEqualTo(LIMIT);
        assertThat(results).hasSize(attempts);
        assertThat(rateLimiterService.getCurrentCount("concurrency-test")).isEqualTo(LIMIT);
    }
}
