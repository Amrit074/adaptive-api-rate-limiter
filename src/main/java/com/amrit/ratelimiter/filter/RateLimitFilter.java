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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;

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

        RateLimiterService.RateLimitResult result = rateLimiterService.checkRateLimit(clientIp);
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
        response.setHeader("X-RateLimit-Window", rateLimiterService.getWindowSeconds() + "s");
        response.setHeader("X-RateLimit-Type", result.type().name());
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
