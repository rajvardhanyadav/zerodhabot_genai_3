package com.tradingbot.repository;

import com.tradingbot.entity.SystemHealthSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for System Health Snapshot entity operations.
 */
@Repository
public interface SystemHealthSnapshotRepository extends JpaRepository<SystemHealthSnapshotEntity, Long> {

    // Find by timestamp range
    List<SystemHealthSnapshotEntity> findByTimestampBetweenOrderByTimestamp(
            LocalDateTime start, LocalDateTime end);

    // Find latest snapshot
    @Query("SELECT s FROM SystemHealthSnapshotEntity s ORDER BY s.timestamp DESC")
    List<SystemHealthSnapshotEntity> findLatest();

    default Optional<SystemHealthSnapshotEntity> findLatestSnapshot() {
        List<SystemHealthSnapshotEntity> results = findLatest();
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    // Find recent snapshots
    List<SystemHealthSnapshotEntity> findTop100ByOrderByTimestampDesc();

    // Find unhealthy snapshots
    @Query("SELECT s FROM SystemHealthSnapshotEntity s WHERE " +
           "(s.kiteConnected = false OR s.websocketConnected = false OR s.databaseHealthy = false) " +
           "AND s.timestamp >= :since ORDER BY s.timestamp DESC")
    List<SystemHealthSnapshotEntity> findUnhealthySnapshotsSince(@Param("since") LocalDateTime since);

    // Average metrics for time range
    @Query("SELECT AVG(s.heapMemoryUsedMB), AVG(s.activeThreads), AVG(s.avgOrderLatencyMs) " +
           "FROM SystemHealthSnapshotEntity s WHERE s.timestamp BETWEEN :start AND :end")
    List<Object[]> getAverageMetricsForDateRange(
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    // Max memory usage
    @Query("SELECT MAX(s.heapMemoryUsedMB) FROM SystemHealthSnapshotEntity s " +
           "WHERE s.timestamp BETWEEN :start AND :end")
    Optional<Long> findMaxHeapMemoryUsedForDateRange(
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    // Count connection failures
    @Query("SELECT COUNT(s) FROM SystemHealthSnapshotEntity s " +
           "WHERE (s.kiteConnected = false OR s.websocketConnected = false) " +
           "AND s.timestamp >= :since")
    long countConnectionFailuresSince(@Param("since") LocalDateTime since);

    // Delete old snapshots
    void deleteByTimestampBefore(LocalDateTime before);

    // Total orders processed
    @Query("SELECT SUM(s.ordersProcessedLastMinute) FROM SystemHealthSnapshotEntity s " +
           "WHERE s.timestamp BETWEEN :start AND :end")
    Optional<Long> sumOrdersProcessedForDateRange(
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    // Total ticks received
    @Query("SELECT SUM(s.ticksReceivedLastMinute) FROM SystemHealthSnapshotEntity s " +
           "WHERE s.timestamp BETWEEN :start AND :end")
    Optional<Long> sumTicksReceivedForDateRange(
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}

