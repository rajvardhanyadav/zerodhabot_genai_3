package com.tradingbot.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Entity for storing system health snapshots.
 * Tracks memory, threads, connections, and performance metrics.
 */
@Entity
@Table(name = "system_health_snapshots", indexes = {
    @Index(name = "idx_health_timestamp", columnList = "timestamp")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemHealthSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    // Memory metrics
    private Long heapMemoryUsedMB;

    private Long heapMemoryMaxMB;

    private Long nonHeapMemoryUsedMB;

    // Thread metrics
    private Integer activeThreads;

    private Integer peakThreads;

    // Connection status
    private Boolean kiteConnected;

    private Boolean websocketConnected;

    private Integer activeWebSocketSubscriptions;

    // Strategy metrics
    private Integer activeStrategies;

    private Integer completedStrategiesToday;

    // Performance metrics
    private Long ticksReceivedLastMinute;

    private Long ordersProcessedLastMinute;

    private Double avgOrderLatencyMs;

    private Double maxOrderLatencyMs;

    // Database metrics
    private Boolean databaseHealthy;

    private Integer activeDbConnections;

    // Paper trading metrics
    private Integer paperOrdersToday;

    private Integer liveOrdersToday;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
    }
}

