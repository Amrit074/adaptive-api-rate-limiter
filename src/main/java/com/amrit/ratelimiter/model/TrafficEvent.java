package com.amrit.ratelimiter.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Persists every incoming request to MySQL.
 * The Python ML service reads this table every 60s to detect anomalies.
 *
 * Fields logged per request:
 * - clientIp       : used as the primary grouping key for anomaly detection
 * - endpoint       : which API path was hit
 * - statusCode     : 200 = passed, 429 = rate limited
 * - latencyMs      : response time in milliseconds
 * - requestCount   : rolling count from Redis at the time of this request
 * - timestamp      : exact time of request
 * - flaggedAnomaly : set to true by ML service if this IP looks suspicious
 */
@Entity
@Table(name = "traffic_log", indexes = {
    @Index(name = "idx_client_ip", columnList = "clientIp"),
    @Index(name = "idx_timestamp", columnList = "timestamp")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrafficEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 45)
    private String clientIp;

    @Column(nullable = false)
    private String endpoint;

    @Column(nullable = false)
    private Integer statusCode;

    @Column(nullable = false)
    private Long latencyMs;

    @Column(nullable = false)
    private Integer requestCount;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column(nullable = false)
    @Builder.Default
    private Boolean flaggedAnomaly = false;
}
