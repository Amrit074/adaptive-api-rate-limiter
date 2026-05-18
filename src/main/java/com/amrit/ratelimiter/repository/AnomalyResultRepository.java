package com.amrit.ratelimiter.repository;

import com.amrit.ratelimiter.model.AnomalyResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AnomalyResultRepository extends JpaRepository<AnomalyResult, Long> {

    // Find results not yet pushed to Redis blocklist
    List<AnomalyResult> findBySyncedToRedisAndIsAnomalous(Boolean synced, Boolean isAnomalous);

    // Check if an IP was flagged recently
    boolean existsByClientIpAndIsAnomalousTrue(String clientIp);
}
