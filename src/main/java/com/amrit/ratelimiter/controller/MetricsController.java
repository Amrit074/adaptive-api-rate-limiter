package com.amrit.ratelimiter.controller;

import com.amrit.ratelimiter.model.TrafficEvent;
import com.amrit.ratelimiter.repository.AnomalyResultRepository;
import com.amrit.ratelimiter.repository.TrafficEventRepository;
import com.amrit.ratelimiter.service.RateLimiterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Tag(name = "Smart Rate Limiter API", description = "Demo, metrics, and admin endpoints")
public class MetricsController {

    private final TrafficEventRepository trafficEventRepository;
    private final AnomalyResultRepository anomalyResultRepository;
    private final RateLimiterService rateLimiterService;

    @GetMapping("/api/metrics/summary")
    @Operation(summary = "Get overall rate limiter statistics")
    public ResponseEntity<Map<String, Object>> getSummary() {
        LocalDateTime lastHour = LocalDateTime.now().minusHours(1);
        LocalDateTime lastDay = LocalDateTime.now().minusDays(1);

        long totalRequests = trafficEventRepository.count();
        long blockedLastHour = trafficEventRepository
            .countByStatusCodeAndTimestampAfter(429, lastHour);
        long allowedLastDay = trafficEventRepository
            .countByStatusCodeAndTimestampAfter(200, lastDay);
        long anomaliesDetected = anomalyResultRepository.count();

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalRequestsAllTime", totalRequests);
        summary.put("blockedRequestsLastHour", blockedLastHour);
        summary.put("allowedRequestsLastDay", allowedLastDay);
        summary.put("totalAnomaliesDetected", anomaliesDetected);
        summary.put("limit", rateLimiterService.getMaxRequests());
        summary.put("windowSeconds", rateLimiterService.getWindowSeconds());
        summary.put("blockTtlSeconds", rateLimiterService.getBlockTtlSeconds());
        summary.put("timestamp", LocalDateTime.now());

        return ResponseEntity.ok(summary);
    }

    @GetMapping("/api/metrics/top-ips")
    @Operation(summary = "Top IPs by request volume in last N minutes")
    public ResponseEntity<List<Map<String, Object>>> getTopIps(
            @RequestParam(defaultValue = "60") int minutes) {

        LocalDateTime since = LocalDateTime.now().minusMinutes(minutes);
        List<Object[]> results = trafficEventRepository.countRequestsPerIpSince(since);

        List<Map<String, Object>> topIps = results.stream()
            .limit(20)
            .map(row -> {
                Map<String, Object> entry = new HashMap<>();
                entry.put("ip", row[0]);
                entry.put("requestCount", row[1]);
                entry.put("currentWindowCount",
                    rateLimiterService.getCurrentCount((String) row[0]));
                return entry;
            })
            .toList();

        return ResponseEntity.ok(topIps);
    }

    @GetMapping("/api/metrics/recent")
    @Operation(summary = "Recent traffic events for demo dashboards")
    public ResponseEntity<List<TrafficEvent>> getRecentTraffic() {
        return ResponseEntity.ok(trafficEventRepository.findTop12ByOrderByTimestampDesc());
    }

    @GetMapping("/api/metrics/anomalies")
    @Operation(summary = "Recent anomaly detections from ML service")
    public ResponseEntity<List<com.amrit.ratelimiter.model.AnomalyResult>> getAnomalies(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(
            anomalyResultRepository.findAll()
                .stream().limit(limit).toList()
        );
    }

    @PostMapping("/api/admin/block/{ip}")
    @Operation(summary = "Manually block an IP address")
    public ResponseEntity<Map<String, String>> blockIp(
            @PathVariable String ip,
            @RequestParam(defaultValue = "Manual block") String reason) {
        rateLimiterService.blockIp(ip, "MANUAL: " + reason);
        return ResponseEntity.ok(Map.of(
            "status", "blocked",
            "ip", ip,
            "reason", reason
        ));
    }

    @DeleteMapping("/api/admin/unblock/{ip}")
    @Operation(summary = "Manually unblock an IP address")
    public ResponseEntity<Map<String, String>> unblockIp(@PathVariable String ip) {
        rateLimiterService.unblockIp(ip);
        return ResponseEntity.ok(Map.of("status", "unblocked", "ip", ip));
    }

    @GetMapping("/api/health/rate-limit/{ip}")
    @Operation(summary = "Check current request count for an IP")
    public ResponseEntity<Map<String, Object>> checkIp(@PathVariable String ip) {
        int count = rateLimiterService.getCurrentCount(ip);
        return ResponseEntity.ok(Map.of(
            "ip", ip,
            "currentWindowCount", count,
            "limit", rateLimiterService.getMaxRequests(),
            "remainingRequests", Math.max(rateLimiterService.getMaxRequests() - count, 0),
            "isAnomalous", anomalyResultRepository.existsByClientIpAndIsAnomalousTrue(ip)
        ));
    }

    @GetMapping("/api/demo/client-ip")
    @Operation(summary = "Return the requester's detected IP address")
    public ResponseEntity<Map<String, Object>> getClientIp(HttpServletRequest request) {
        String clientIp = extractClientIp(request);
        int count = rateLimiterService.getCurrentCount(clientIp);
        return ResponseEntity.ok(Map.of(
            "clientIp", clientIp,
            "currentWindowCount", count,
            "limit", rateLimiterService.getMaxRequests(),
            "remainingRequests", Math.max(rateLimiterService.getMaxRequests() - count, 0),
            "timestamp", LocalDateTime.now().toString()
        ));
    }

    @GetMapping("/api/demo/config")
    @Operation(summary = "Return demo rate-limit settings")
    public ResponseEntity<Map<String, Object>> getDemoConfig() {
        return ResponseEntity.ok(Map.of(
            "limit", rateLimiterService.getMaxRequests(),
            "windowSeconds", rateLimiterService.getWindowSeconds(),
            "blockTtlSeconds", rateLimiterService.getBlockTtlSeconds(),
            "protectedEndpoints", List.of("/api/test", "/api/hello")
        ));
    }

    @GetMapping("/api/test")
    @Operation(summary = "Protected demo endpoint - hit repeatedly to trigger 429")
    public ResponseEntity<Map<String, Object>> test(HttpServletRequest request) {
        return ResponseEntity.ok(Map.of(
            "message", "Request allowed",
            "endpoint", "/api/test",
            "clientIp", extractClientIp(request),
            "timestamp", LocalDateTime.now().toString()
        ));
    }

    @GetMapping("/api/hello")
    @Operation(summary = "Protected demo endpoint - try hitting this to trigger rate limiting")
    public ResponseEntity<Map<String, Object>> hello(HttpServletRequest request) {
        return ResponseEntity.ok(Map.of(
            "message", "Hello! This endpoint is rate limited.",
            "endpoint", "/api/hello",
            "clientIp", extractClientIp(request),
            "timestamp", LocalDateTime.now().toString()
        ));
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
