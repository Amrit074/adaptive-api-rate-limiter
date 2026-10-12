package com.amrit.ratelimiter.service;

import com.amrit.ratelimiter.filter.RateLimitFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class RateLimitMetricsTest {
    @Test void failOpenHasSeparateMetricAndResponseHeader() throws Exception {
        var limiter = mock(RateLimiterService.class);
        when(limiter.checkRateLimit("127.0.0.1")).thenReturn(RateLimiterService.RateLimitResult.failOpen(5, 60, "offline"));
        var registry = new SimpleMeterRegistry();
        var filter = new RateLimitFilter(limiter, mock(TrafficLoggingService.class), new ObjectMapper(), registry, mock(ClientPolicyService.class), mock(PolicyLimiter.class));
        var request = new MockHttpServletRequest("GET", "/api/test");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getHeader("X-RateLimit-Type")).isEqualTo("FAIL_OPEN");
        assertThat(registry.get("rate_limiter.decisions").tag("outcome", "FAIL_OPEN").counter().count()).isEqualTo(1);
        assertThat(registry.get("rate_limiter.redis_errors").counter().count()).isEqualTo(1);
        assertThat(registry.get("rate_limiter.decision").timer().count()).isEqualTo(1);
    }
}
