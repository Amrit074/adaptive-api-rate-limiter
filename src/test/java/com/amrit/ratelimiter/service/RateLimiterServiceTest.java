package com.amrit.ratelimiter.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RateLimiterService.
 * Uses Mockito to mock Redis, so no real Redis instance is needed.
 */
@ExtendWith(MockitoExtension.class)
class RateLimiterServiceTest {

    @Mock(name = "rateLimitTemplate")
    private RedisTemplate<String, String> rateLimitTemplate;

    @Mock(name = "blocklistTemplate")
    private RedisTemplate<String, String> blocklistTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOps;

    @Mock
    private ValueOperations<String, String> valueOps;

    private RateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        rateLimiterService = new RateLimiterService(rateLimitTemplate, blocklistTemplate);

        ReflectionTestUtils.setField(rateLimiterService, "maxRequests", 100);
        ReflectionTestUtils.setField(rateLimiterService, "windowSeconds", 60);
        ReflectionTestUtils.setField(rateLimiterService, "blockTtlSeconds", 300);
    }

    @Test
    @DisplayName("Should allow request when under rate limit")
    void shouldAllowRequestUnderLimit() {
        String ip = "192.168.1.1";
        when(rateLimitTemplate.opsForZSet()).thenReturn(zSetOps);
        when(blocklistTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("block:" + ip)).thenReturn(null);
        when(zSetOps.removeRangeByScore(anyString(), anyDouble(), anyDouble())).thenReturn(0L);
        when(zSetOps.zCard(anyString())).thenReturn(5L);
        when(zSetOps.add(anyString(), anyString(), anyDouble())).thenReturn(true);

        RateLimiterService.RateLimitResult result = rateLimiterService.checkRateLimit(ip);

        assertThat(result.allowed()).isTrue();
        assertThat(result.requestCount()).isEqualTo(6);
        assertThat(result.type()).isEqualTo(RateLimiterService.RateLimitResult.ResultType.ALLOWED);
    }

    @Test
    @DisplayName("Should block request when rate limit exceeded")
    void shouldBlockRequestWhenLimitExceeded() {
        String ip = "192.168.1.2";
        when(rateLimitTemplate.opsForZSet()).thenReturn(zSetOps);
        when(blocklistTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("block:" + ip)).thenReturn(null);
        when(zSetOps.removeRangeByScore(anyString(), anyDouble(), anyDouble())).thenReturn(0L);
        when(zSetOps.zCard(anyString())).thenReturn(100L);

        RateLimiterService.RateLimitResult result = rateLimiterService.checkRateLimit(ip);

        assertThat(result.allowed()).isFalse();
        assertThat(result.type()).isEqualTo(RateLimiterService.RateLimitResult.ResultType.RATE_LIMITED);
        assertThat(result.reason()).contains("Rate limit exceeded");
        verify(zSetOps, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    @DisplayName("Should block request from anomaly blocklist before checking sliding window")
    void shouldBlockAnomalousIpImmediately() {
        String ip = "10.0.0.1";
        String blockReason = "ML_ANOMALY score=-0.432 reason=high_rate=120.0req/min";
        when(blocklistTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("block:" + ip)).thenReturn(blockReason);

        RateLimiterService.RateLimitResult result = rateLimiterService.checkRateLimit(ip);

        assertThat(result.allowed()).isFalse();
        assertThat(result.type()).isEqualTo(RateLimiterService.RateLimitResult.ResultType.ANOMALY_BLOCKED);
        assertThat(result.reason()).contains("ANOMALY_BLOCK");
        verify(zSetOps, never()).zCard(anyString());
        verify(zSetOps, never()).removeRangeByScore(anyString(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("Should set TTL when blocking an IP")
    void shouldSetTtlWhenBlockingIp() {
        String ip = "10.0.0.2";
        String reason = "MANUAL: suspicious activity";
        when(blocklistTemplate.opsForValue()).thenReturn(valueOps);

        rateLimiterService.blockIp(ip, reason);

        verify(valueOps).set(
            eq("block:" + ip),
            eq(reason),
            eq(300L),
            any()
        );
    }

    @Test
    @DisplayName("Should delete blocklist key when unblocking an IP")
    void shouldDeleteKeyWhenUnblockingIp() {
        String ip = "10.0.0.3";

        rateLimiterService.unblockIp(ip);

        verify(blocklistTemplate).delete("block:" + ip);
    }
}
