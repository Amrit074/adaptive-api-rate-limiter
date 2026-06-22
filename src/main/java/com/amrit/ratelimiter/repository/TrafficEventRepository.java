package com.amrit.ratelimiter.repository;

import com.amrit.ratelimiter.model.TrafficEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TrafficEventRepository extends JpaRepository<TrafficEvent, Long> {

    // Used by metrics endpoint: count requests per IP in last N minutes
    @Query("SELECT t.clientIp, COUNT(t) FROM TrafficEvent t " +
           "WHERE t.timestamp >= :since GROUP BY t.clientIp ORDER BY COUNT(t) DESC")
    List<Object[]> countRequestsPerIpSince(LocalDateTime since);

    // Used by metrics endpoint: count rate-limited requests
    long countByStatusCodeAndTimestampAfter(int statusCode, LocalDateTime since);

    // Used to feed the ML service (recent window only)
    List<TrafficEvent> findByTimestampAfterOrderByTimestampDesc(LocalDateTime since);

    List<TrafficEvent> findTop12ByOrderByTimestampDesc();
}
