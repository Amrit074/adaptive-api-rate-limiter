package com.amrit.ratelimiter.controller;

import com.amrit.ratelimiter.repository.AnomalyResultRepository;
import com.amrit.ratelimiter.repository.TrafficEventRepository;
import com.amrit.ratelimiter.service.RateLimiterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for monitoring and administration.
 *
 * Endpoints:
 * GET  /api/metrics/summary        - overall stats (requests, blocks, anomalies)
 * GET  /api/metrics/top-ips        - top IPs by request volume
 * GET  /api/metrics/anomalies      - recent anomaly detections
 * POST /api/admin/block/{ip}       - manually block an IP
 * DELETE /api/admin/unblock/{ip}   - manually unblock an IP
 * GET  /api/health/rate-limit/{ip} - check current count for an IP
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Smart Rate Limiter API", description = "Metrics and admin endpoints")
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
        long totalLastDay = trafficEventRepository
            .countByStatusCodeAndTimestampAfter(200, lastDay);
        long anomaliesDetected = anomalyResultRepository.count();

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalRequestsAllTime", totalRequests);
        summary.put("blockedRequestsLastHour", blockedLastHour);
        summary.put("allowedRequestsLastDay", totalLastDay);
        summary.put("totalAnomaliesDetected", anomaliesDetected);
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
            "isAnomalous", anomalyResultRepository.existsByClientIpAndIsAnomalousTrue(ip)
        ));
    }

    // Demo endpoint to test rate limiting
    @GetMapping("/api/hello")
    @Operation(summary = "Demo endpoint — try hitting this to trigger rate limiting")
    public ResponseEntity<Map<String, String>> hello() {
        return ResponseEntity.ok(Map.of(
            "message", "Hello! This endpoint is rate limited.",
            "timestamp", LocalDateTime.now().toString()
        ));
    }
}
