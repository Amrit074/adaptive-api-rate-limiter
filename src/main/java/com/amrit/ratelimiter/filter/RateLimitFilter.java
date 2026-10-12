package com.amrit.ratelimiter.filter;

import com.amrit.ratelimiter.service.RateLimiterService;
import com.amrit.ratelimiter.service.TrafficLoggingService;
import com.amrit.ratelimiter.service.ClientPolicyService;
import com.amrit.ratelimiter.service.PolicyLimiter;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;

/**
 * Central request interceptor for protected demo/API endpoints.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiterService rateLimiterService;
    private final TrafficLoggingService trafficLoggingService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final ClientPolicyService clientPolicyService;
    private final PolicyLimiter policyLimiter;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/") ||
            path.equals("/index.html") ||
            path.equals("/favicon.ico") ||
            path.startsWith("/assets/") ||
            path.startsWith("/actuator") ||
            path.startsWith("/swagger-ui") ||
            path.startsWith("/api-docs") ||
            path.startsWith("/api/metrics") ||
            path.startsWith("/api/demo") ||
            path.startsWith("/api/admin") ||
            path.startsWith("/api/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String clientIp = extractClientIp(request);
        String endpoint = request.getRequestURI();
        long startTime = System.currentTimeMillis();

        long decisionStart = System.nanoTime();
        RateLimiterService.RateLimitResult result;
        String apiKey = request.getHeader("X-API-Key");
        if(apiKey != null && !apiKey.isBlank()) {
            try {
                var client = clientPolicyService.authenticate(apiKey);
                response.setHeader("X-RateLimit-Policy", client.getName());
                result = policyLimiter.check(client, clientIp);
            } catch(ResponseStatusException e) {
                response.setStatus(e.getStatusCode().value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"message\":\"Invalid or disabled API key\"}"); return;
            } catch(Exception e) {
                response.setStatus(503); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"message\":\"Client authentication temporarily unavailable\"}"); return;
            }
        } else {
            response.setHeader("X-RateLimit-Policy", "Anonymous IP");
            result = rateLimiterService.checkRateLimit(clientIp);
        }
        // Fixed outcome labels only: never put IP addresses or arbitrary paths in metrics.
        String outcome = result.type().name();
        meterRegistry.counter("rate_limiter.decisions", "outcome", outcome).increment();
        meterRegistry.timer("rate_limiter.decision", "outcome", outcome)
            .record(System.nanoTime() - decisionStart, TimeUnit.NANOSECONDS);
        if (result.type() == RateLimiterService.RateLimitResult.ResultType.FAIL_OPEN) {
            meterRegistry.counter("rate_limiter.redis_errors").increment();
        }
        addRateLimitHeaders(response, result);

        if (result.allowed()) {
            filterChain.doFilter(request, response);

            long latency = System.currentTimeMillis() - startTime;
            trafficLoggingService.logRequest(clientIp, endpoint,
                response.getStatus(), latency, result.requestCount());
            return;
        }

        long latency = System.currentTimeMillis() - startTime;
        trafficLoggingService.logRequest(clientIp, endpoint, 429, latency, result.requestCount());
        sendBlockedResponse(response, result);
    }

    private void addRateLimitHeaders(HttpServletResponse response, RateLimiterService.RateLimitResult result) {
        response.setHeader("X-RateLimit-Limit", String.valueOf(result.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remainingRequests()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(result.resetSeconds()));
        response.setHeader("X-RateLimit-Type", result.type().name());
        response.setHeader("X-RateLimit-Reason", result.reason());
        if (!result.allowed()) {
            response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
        }
    }

    private void sendBlockedResponse(HttpServletResponse response,
                                     RateLimiterService.RateLimitResult result) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        Map<String, Object> body = Map.of(
            "status", 429,
            "error", "Too Many Requests",
            "message", "Rate limit exceeded. Please try again later.",
            "blockType", result.type().name(),
            "reason", result.reason(),
            "limit", result.limit(),
            "remainingRequests", result.remainingRequests(),
            "retryAfterSeconds", result.retryAfterSeconds(),
            "timestamp", LocalDateTime.now().toString()
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private String extractClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
