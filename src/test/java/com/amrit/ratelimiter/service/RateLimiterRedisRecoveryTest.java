package com.amrit.ratelimiter.service;

import com.amrit.ratelimiter.config.RedisConfig;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static com.amrit.ratelimiter.service.RateLimiterService.RateLimitResult.ResultType.*;

/** Faults affect only this disposable container, never a developer's Redis. */
@Testcontainers
class RateLimiterRedisRecoveryTest {
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379)
        .withCommand("sh", "-c", "redis-server --daemonize yes; while true; do sleep 1; done");
    LettuceConnectionFactory factory;
    StringRedisTemplate template;
    RateLimiterService limiter;

    @BeforeEach void setup() {
        var config = new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379));
        factory = new LettuceConnectionFactory(config, LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(300)).shutdownTimeout(Duration.ZERO).build());
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        limiter = new RateLimiterService(template, template, new RedisConfig().slidingWindowRateLimitScript());
        ReflectionTestUtils.setField(limiter, "maxRequests", 2);
        ReflectionTestUtils.setField(limiter, "windowSeconds", 1);
        ReflectionTestUtils.setField(limiter, "blockTtlSeconds", 1);
    }
    @AfterEach void cleanup() { factory.destroy(); }

    @Test void countersAndBlocklistExpire() {
        assertThat(limiter.checkRateLimit("expiry").type()).isEqualTo(ALLOWED);
        assertThat(limiter.checkRateLimit("expiry").type()).isEqualTo(ALLOWED);
        assertThat(limiter.checkRateLimit("expiry").type()).isEqualTo(RATE_LIMITED);
        await().atMost(Duration.ofSeconds(4)).untilAsserted(() ->
            assertThat(template.hasKey("rl:expiry")).isFalse());
        assertThat(limiter.checkRateLimit("expiry").type()).isEqualTo(ALLOWED);
        limiter.blockIp("blocked", "test");
        assertThat(limiter.checkRateLimit("blocked").type()).isEqualTo(ANOMALY_BLOCKED);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
            assertThat(limiter.checkRateLimit("blocked").type()).isEqualTo(ALLOWED));
    }

    @Test void latencyBeyondCommandTimeoutFailsOpenAndRecovers() throws Exception {
        assertThat(limiter.checkRateLimit("latency").type()).isEqualTo(ALLOWED);
        // Redis deliberately delays all commands; the client must time out safely.
        redis.execInContainer("redis-cli", "CLIENT", "PAUSE", "1500", "ALL");
        long start = System.nanoTime();
        assertThat(limiter.checkRateLimit("latency").type()).isEqualTo(FAIL_OPEN);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() ->
            assertThat(limiter.checkRateLimit("recovered-latency").type()).isEqualTo(ALLOWED));
    }

    @Test void disconnectedRedisFailsOpenAndRestartRestoresEnforcement() throws Exception {
        assertThat(limiter.checkRateLimit("outage").type()).isEqualTo(ALLOWED);
        redis.execInContainer("redis-cli", "SHUTDOWN", "NOSAVE");
        try {
            assertThat(limiter.checkRateLimit("outage").type()).isEqualTo(FAIL_OPEN);
        } finally {
            redis.execInContainer("redis-server", "--daemonize", "yes");
        }
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(limiter.checkRateLimit("restart").type()).isEqualTo(ALLOWED));
        ReflectionTestUtils.setField(limiter, "windowSeconds", 60);
        assertThat(limiter.checkRateLimit("post-restart").type()).isEqualTo(ALLOWED);
        assertThat(limiter.checkRateLimit("post-restart").type()).isEqualTo(ALLOWED);
        assertThat(limiter.checkRateLimit("post-restart").type()).isEqualTo(RATE_LIMITED);
    }
}
