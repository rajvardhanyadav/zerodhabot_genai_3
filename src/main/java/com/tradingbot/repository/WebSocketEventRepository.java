package com.tradingbot.repository;

import com.tradingbot.entity.WebSocketEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for WebSocket Event entity operations.
 */
@Repository
public interface WebSocketEventRepository extends JpaRepository<WebSocketEventEntity, Long> {

    // Find by timestamp range
    List<WebSocketEventEntity> findByTimestampBetweenOrderByTimestampDesc(
            LocalDateTime start, LocalDateTime end);

    // Find by user and timestamp range
    List<WebSocketEventEntity> findByUserIdAndTimestampBetweenOrderByTimestampDesc(
            String userId, LocalDateTime start, LocalDateTime end);

    // Find by event type
    List<WebSocketEventEntity> findByEventTypeOrderByTimestampDesc(String eventType);

    // Find recent events
    List<WebSocketEventEntity> findTop100ByOrderByTimestampDesc();

    // Find recent events by user
    List<WebSocketEventEntity> findTop100ByUserIdOrderByTimestampDesc(String userId);

    // Count errors since time
    @Query("SELECT COUNT(w) FROM WebSocketEventEntity w WHERE w.eventType = 'ERROR' AND w.timestamp >= :since")
    long countErrorsSince(@Param("since") LocalDateTime since);

    // Count disconnections since time
    @Query("SELECT COUNT(w) FROM WebSocketEventEntity w WHERE w.eventType = 'DISCONNECTED' AND w.timestamp >= :since")
    long countDisconnectionsSince(@Param("since") LocalDateTime since);

    // Find recent errors
    @Query("SELECT w FROM WebSocketEventEntity w WHERE w.eventType = 'ERROR' ORDER BY w.timestamp DESC")
    List<WebSocketEventEntity> findRecentErrors();

    // Find events with high reconnect attempts
    List<WebSocketEventEntity> findByReconnectAttemptGreaterThanOrderByTimestampDesc(Integer threshold);

    // Delete old events
    void deleteByTimestampBefore(LocalDateTime before);

    // Statistics by event type
    @Query("SELECT w.eventType, COUNT(w) FROM WebSocketEventEntity w " +
           "WHERE w.timestamp BETWEEN :start AND :end GROUP BY w.eventType")
    List<Object[]> countByEventTypeForDateRange(
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    // Average latency
    @Query("SELECT AVG(w.latencyMs) FROM WebSocketEventEntity w " +
           "WHERE w.eventType = 'CONNECTED' AND w.timestamp BETWEEN :start AND :end")
    Double getAverageConnectionLatencyForDateRange(
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}

