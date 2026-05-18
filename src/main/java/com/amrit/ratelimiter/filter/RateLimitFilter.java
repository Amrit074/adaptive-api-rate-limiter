package com.amrit.ratelimiter.filter;

import com.amrit.ratelimiter.service.RateLimiterService;
import com.amrit.ratelimiter.service.TrafficLoggingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Central request interceptor. Every HTTP request passes through this filter.
 *
 * Responsibilities:
 * 1. Extract client IP (respects X-Forwarded-For for proxy setups)
 * 2. Check rate limit + anomaly blocklist via RateLimiterService
 * 3. If allowed: measure latency, pass to controller, log async
 * 4. If blocked: return 429 JSON immediately with retry-after header
 *
 * Excluded paths: /actuator/**, /swagger-ui/**, /api-docs/**
 * (monitoring and docs should never be rate-limited)
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiterService rateLimiterService;
    private final TrafficLoggingService trafficLoggingService;
    private final ObjectMapper objectMapper;

    @Value("${rate-limiter.window-seconds:60}")
    private int windowSeconds;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator") ||
               path.startsWith("/swagger-ui") ||
               path.startsWith("/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String clientIp = extractClientIp(request);
        String endpoint = request.getRequestURI();
        long startTime = System.currentTimeMillis();

        // Check rate limit and anomaly blocklist
        RateLimiterService.RateLimitResult result = rateLimiterService.checkRateLimit(clientIp);

        if (result.allowed()) {
            // Add rate limit headers to response (like GitHub's API does)
            response.setHeader("X-RateLimit-Limit", String.valueOf(
                rateLimiterService.getCurrentCount(clientIp)));
            response.setHeader("X-RateLimit-Window", windowSeconds + "s");
            response.setHeader("X-RateLimit-Type", result.type().name());

            // Pass to actual controller
            filterChain.doFilter(request, response);

            // Log async after response is sent (doesn't add latency)
            long latency = System.currentTimeMillis() - startTime;
            trafficLoggingService.logRequest(clientIp, endpoint,
                response.getStatus(), latency, result.requestCount());

        } else {
            // Blocked: return 429 immediately
            long latency = System.currentTimeMillis() - startTime;
            trafficLoggingService.logRequest(clientIp, endpoint, 429, latency, result.requestCount());
            sendBlockedResponse(response, result);
        }
    }

    private void sendBlockedResponse(HttpServletResponse response,
                                     RateLimiterService.RateLimitResult result) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(windowSeconds));
        response.setHeader("X-Block-Type", result.type().name());

        Map<String, Object> body = Map.of(
            "status", 429,
            "error", "Too Many Requests",
            "blockType", result.type().name(),
            "reason", result.reason(),
            "retryAfter", windowSeconds + "s"
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    /**
     * Extracts real client IP, respecting reverse proxy headers.
     * X-Forwarded-For can be spoofed — in production, only trust this
     * header from known proxy IPs. For this project scope, it's fine.
     */
    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }
        return request.getRemoteAddr();
    }
}
