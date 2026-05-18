package com.amrit.ratelimiter.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Core sliding window rate limiter backed by Redis.
 *
 * Algorithm:
 * - Each IP gets a Redis Sorted Set: key = "rl:{ip}"
 * - Each request is stored as a member = timestamp (millis), score = timestamp
 * - On each request:
 *   1. Remove all members older than (now - windowMs) → trim the window
 *   2. Count remaining members → current request count
 *   3. If count < maxRequests: add current timestamp, allow request
 *   4. If count >= maxRequests: reject with 429
 * - The Sorted Set TTL is refreshed to windowSeconds on every hit
 *
 * Why Sorted Set vs simple counter:
 * - Simple counter loses precision at window boundaries (fixed window problem)
 * - Sorted Set gives exact sliding window: always counts exactly the last N seconds
 * - Tradeoff: slightly more memory per IP (each request = one entry in the set)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RateLimiterService {

    @Qualifier("rateLimitTemplate")
    private final RedisTemplate<String, String> redisTemplate;

    @Qualifier("blocklistTemplate")
    private final RedisTemplate<String, String> blocklistTemplate;

    @Value("${rate-limiter.max-requests:100}")
    private int maxRequests;

    @Value("${rate-limiter.window-seconds:60}")
    private int windowSeconds;

    @Value("${rate-limiter.block-ttl-seconds:300}")
    private int blockTtlSeconds;

    private static final String RATE_LIMIT_KEY_PREFIX = "rl:";
    private static final String BLOCK_KEY_PREFIX = "block:";

    /**
     * Core rate limit check.
     * Returns RateLimitResult containing: allowed/blocked, current count, and reason.
     */
    public RateLimitResult checkRateLimit(String clientIp) {
        // 1. Check anomaly blocklist first (fastest path — O(1) Redis GET)
        String blockReason = isBlocked(clientIp);
        if (blockReason != null) {
            log.debug("IP {} blocked by anomaly blocklist: {}", clientIp, blockReason);
            return RateLimitResult.blocked(0, "ANOMALY_BLOCK: " + blockReason);
        }

        // 2. Sliding window check
        String key = RATE_LIMIT_KEY_PREFIX + clientIp;
        long nowMs = Instant.now().toEpochMilli();
        long windowMs = windowSeconds * 1000L;
        long windowStart = nowMs - windowMs;

        // Remove expired entries outside the current window
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStart);

        // Count requests in current window
        Long currentCount = redisTemplate.opsForZSet().zCard(key);
        int count = (currentCount == null) ? 0 : currentCount.intValue();

        if (count >= maxRequests) {
            log.debug("IP {} rate limited: {}/{} requests in window", clientIp, count, maxRequests);
            return RateLimitResult.rateLimited(count,
                "Rate limit exceeded: " + count + "/" + maxRequests + " requests in " + windowSeconds + "s");
        }

        // Allow: add this request timestamp to the sorted set
        String member = String.valueOf(nowMs);
        redisTemplate.opsForZSet().add(key, member, nowMs);
        // Refresh TTL so the key expires naturally when idle
        redisTemplate.expire(key, windowSeconds * 2L, TimeUnit.SECONDS);

        return RateLimitResult.allowed(count + 1);
    }

    /**
     * Returns the current request count for an IP without modifying state.
     * Used by the metrics endpoint.
     */
    public int getCurrentCount(String clientIp) {
        String key = RATE_LIMIT_KEY_PREFIX + clientIp;
        long windowStart = Instant.now().toEpochMilli() - (windowSeconds * 1000L);
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStart);
        Long count = redisTemplate.opsForZSet().zCard(key);
        return count == null ? 0 : count.intValue();
    }

    /**
     * Adds an IP to the Redis blocklist with a TTL.
     * Called by AnomalySyncService after ML detection.
     */
    public void blockIp(String ip, String reason) {
        String key = BLOCK_KEY_PREFIX + ip;
        blocklistTemplate.opsForValue().set(key, reason, blockTtlSeconds, TimeUnit.SECONDS);
        log.info("IP {} added to blocklist for {}s. Reason: {}", ip, blockTtlSeconds, reason);
    }

    /**
     * Manually unblock an IP (admin endpoint use).
     */
    public void unblockIp(String ip) {
        blocklistTemplate.delete(BLOCK_KEY_PREFIX + ip);
        log.info("IP {} removed from blocklist", ip);
    }

    /**
     * Returns block reason if IP is blocked, null if not blocked.
     */
    private String isBlocked(String ip) {
        return blocklistTemplate.opsForValue().get(BLOCK_KEY_PREFIX + ip);
    }

    // ── Inner result class ────────────────────────────────────────────────

    public record RateLimitResult(
        boolean allowed,
        int requestCount,
        String reason,
        ResultType type
    ) {
        public enum ResultType { ALLOWED, RATE_LIMITED, ANOMALY_BLOCKED }

        static RateLimitResult allowed(int count) {
            return new RateLimitResult(true, count, "OK", ResultType.ALLOWED);
        }
        static RateLimitResult rateLimited(int count, String reason) {
            return new RateLimitResult(false, count, reason, ResultType.RATE_LIMITED);
        }
        static RateLimitResult blocked(int count, String reason) {
            return new RateLimitResult(false, count, reason, ResultType.ANOMALY_BLOCKED);
        }
    }
}
