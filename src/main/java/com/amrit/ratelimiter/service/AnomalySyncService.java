package com.amrit.ratelimiter.service;

import com.amrit.ratelimiter.model.AnomalyResult;
import com.amrit.ratelimiter.repository.AnomalyResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Scheduled service that bridges the Python ML service with the Redis blocklist.
 *
 * Flow:
 * 1. Python ML service writes anomaly detections to anomaly_result table (MySQL)
 * 2. This service polls that table every 30 seconds
 * 3. For each unseen anomalous IP, it calls RateLimiterService.blockIp()
 * 4. Marks the anomaly_result row as synced so it won't be re-processed
 *
 * This decoupled design means:
 * - The ML service can be any language/framework
 * - Spring never blocks on ML inference
 * - Redis blocklist is always up-to-date within 30s of detection
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AnomalySyncService {

    private final AnomalyResultRepository anomalyResultRepository;
    private final RateLimiterService rateLimiterService;

    @Scheduled(fixedDelay = 30_000)  // every 30 seconds
    @Transactional
    public void syncAnomalyBlocklist() {
        List<AnomalyResult> unsynced = anomalyResultRepository
            .findBySyncedToRedisAndIsAnomalous(false, true);

        if (unsynced.isEmpty()) return;

        log.info("Syncing {} anomalous IPs to Redis blocklist", unsynced.size());

        for (AnomalyResult result : unsynced) {
            try {
                String reason = String.format("ML_ANOMALY score=%.3f reason=%s",
                    result.getAnomalyScore(), result.getReason());
                rateLimiterService.blockIp(result.getClientIp(), reason);
                result.setSyncedToRedis(true);
                anomalyResultRepository.save(result);
                log.info("Blocked anomalous IP: {} | Score: {} | Reason: {}",
                    result.getClientIp(), result.getAnomalyScore(), result.getReason());
            } catch (Exception e) {
                log.error("Failed to sync anomaly for IP {}: {}", result.getClientIp(), e.getMessage());
            }
        }
    }
}
