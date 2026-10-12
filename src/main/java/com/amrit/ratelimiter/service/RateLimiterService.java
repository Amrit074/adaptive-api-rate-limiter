package com.amrit.ratelimiter.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Core sliding-window rate limiter backed by Redis Sorted Sets.
 */
@Service
@Slf4j
public class RateLimiterService {

    @Qualifier("rateLimitTemplate")
    private final RedisTemplate<String, String> redisTemplate;

    @Qualifier("blocklistTemplate")
    private final RedisTemplate<String, String> blocklistTemplate;

    @Qualifier("slidingWindowRateLimitScript")
    private final DefaultRedisScript<String> slidingWindowRateLimitScript;

    public RateLimiterService(
        @Qualifier("rateLimitTemplate") RedisTemplate<String, String> redisTemplate,
        @Qualifier("blocklistTemplate") RedisTemplate<String, String> blocklistTemplate,
        @Qualifier("slidingWindowRateLimitScript") DefaultRedisScript<String> slidingWindowRateLimitScript
    ) {
        this.redisTemplate = redisTemplate;
        this.blocklistTemplate = blocklistTemplate;
        this.slidingWindowRateLimitScript = slidingWindowRateLimitScript;
    }

    @Value("${rate-limiter.max-requests:5}")
    private int maxRequests;

    @Value("${rate-limiter.window-seconds:60}")
    private int windowSeconds;

    @Value("${rate-limiter.block-ttl-seconds:300}")
    private int blockTtlSeconds;

    private static final String RATE_LIMIT_KEY_PREFIX = "rl:";
    private static final String BLOCK_KEY_PREFIX = "block:";

    public RateLimitResult checkRateLimit(String clientIp) {
        try {
            String blockReason = isBlocked(clientIp);
            if (blockReason != null) {
                log.debug("IP {} blocked by anomaly blocklist: {}", clientIp, blockReason);
                return RateLimitResult.blocked(0, maxRequests, windowSeconds,
                    "ANOMALY_BLOCK: " + blockReason);
            }

            String key = RATE_LIMIT_KEY_PREFIX + clientIp;
            long nowMs = Instant.now().toEpochMilli();
            long windowMs = windowSeconds * 1000L;
            long windowStart = nowMs - windowMs;

            String scriptResult = redisTemplate.execute(
                slidingWindowRateLimitScript,
                List.of(key),
                String.valueOf(nowMs),
                String.valueOf(windowStart),
                String.valueOf(maxRequests),
                String.valueOf(windowSeconds * 2L),
                nowMs + ":" + UUID.randomUUID(),
                String.valueOf(windowMs)
            );
            ScriptResult decision = ScriptResult.parse(scriptResult);

            if (!decision.allowed()) {
                int count = decision.requestCount();
                log.debug("IP {} rate limited: {}/{} requests in window", clientIp, count, maxRequests);
                return RateLimitResult.rateLimited(count, maxRequests, decision.retryAfterSeconds(),
                    "Rate limit exceeded: " + count + "/" + maxRequests + " requests in " + windowSeconds + "s");
            }

            return RateLimitResult.allowed(decision.requestCount(), maxRequests, windowSeconds);
        } catch (Exception e) {
            log.warn("Redis rate-limit check failed for IP {}. Failing open: {}", clientIp, e.getMessage());
            return RateLimitResult.failOpen(maxRequests, windowSeconds,
                "Redis unavailable; request allowed to preserve API availability");
        }
    }

    public int getCurrentCount(String clientIp) {
        try {
            String key = RATE_LIMIT_KEY_PREFIX + clientIp;
            long windowStart = Instant.now().toEpochMilli() - (windowSeconds * 1000L);
            redisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStart);
            Long count = redisTemplate.opsForZSet().zCard(key);
            return count == null ? 0 : count.intValue();
        } catch (Exception e) {
            log.warn("Unable to read current rate-limit count for IP {}: {}", clientIp, e.getMessage());
            return 0;
        }
    }

    public void blockIp(String ip, String reason) {
        String key = BLOCK_KEY_PREFIX + ip;
        blocklistTemplate.opsForValue().set(key, reason, blockTtlSeconds, TimeUnit.SECONDS);
        log.info("IP {} added to blocklist for {}s. Reason: {}", ip, blockTtlSeconds, reason);
    }

    public void unblockIp(String ip) {
        blocklistTemplate.delete(BLOCK_KEY_PREFIX + ip);
        log.info("IP {} removed from blocklist", ip);
    }

    public int getMaxRequests() {
        return maxRequests;
    }

    public int getWindowSeconds() {
        return windowSeconds;
    }

    public int getBlockTtlSeconds() {
        return blockTtlSeconds;
    }

    private String isBlocked(String ip) {
        return blocklistTemplate.opsForValue().get(BLOCK_KEY_PREFIX + ip);
    }

    public record RateLimitResult(
        boolean allowed,
        int requestCount,
        int limit,
        int remainingRequests,
        int retryAfterSeconds,
        int resetSeconds,
        String reason,
        ResultType type
    ) {
        public enum ResultType { ALLOWED, RATE_LIMITED, ANOMALY_BLOCKED, FAIL_OPEN }

        static RateLimitResult allowed(int count, int limit, int windowSeconds) {
            return new RateLimitResult(true, count, limit, Math.max(limit - count, 0),
                0, windowSeconds, "OK", ResultType.ALLOWED);
        }

        static RateLimitResult rateLimited(int count, int limit, int retryAfterSeconds, String reason) {
            return new RateLimitResult(false, count, limit, 0,
                retryAfterSeconds, retryAfterSeconds, reason, ResultType.RATE_LIMITED);
        }

        static RateLimitResult blocked(int count, int limit, int windowSeconds, String reason) {
            return new RateLimitResult(false, count, limit, 0,
                windowSeconds, windowSeconds, reason, ResultType.ANOMALY_BLOCKED);
        }

        static RateLimitResult failOpen(int limit, int windowSeconds, String reason) {
            return new RateLimitResult(true, 0, limit, limit,
                0, windowSeconds, reason, ResultType.FAIL_OPEN);
        }
    }

    private record ScriptResult(boolean allowed, int requestCount, int retryAfterSeconds) {
        private static ScriptResult parse(String value) {
            if (value == null) {
                throw new IllegalStateException("Rate-limit Lua script returned no result");
            }

            String[] parts = value.split(":", -1);
            if (parts.length != 3) {
                throw new IllegalStateException("Unexpected rate-limit Lua script result: " + value);
            }

            return new ScriptResult(
                "1".equals(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2])
            );
        }
    }
}
