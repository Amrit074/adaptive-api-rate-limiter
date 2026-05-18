package com.amrit.ratelimiter.service;

import com.amrit.ratelimiter.model.TrafficEvent;
import com.amrit.ratelimiter.repository.TrafficEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Logs every request to MySQL asynchronously.
 * The @Async annotation means this does NOT add to the request's latency.
 * The Python ML service reads this table every 60 seconds.
 *
 * This is the data pipeline feeding the anomaly detector.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TrafficLoggingService {

    private final TrafficEventRepository repository;

    @Async
    public void logRequest(String clientIp, String endpoint,
                           int statusCode, long latencyMs, int requestCount) {
        try {
            TrafficEvent event = TrafficEvent.builder()
                .clientIp(clientIp)
                .endpoint(endpoint)
                .statusCode(statusCode)
                .latencyMs(latencyMs)
                .requestCount(requestCount)
                .timestamp(LocalDateTime.now())
                .flaggedAnomaly(false)
                .build();
            repository.save(event);
        } catch (Exception e) {
            // Never let logging failure affect the main request path
            log.error("Failed to log traffic event for IP {}: {}", clientIp, e.getMessage());
        }
    }
}
