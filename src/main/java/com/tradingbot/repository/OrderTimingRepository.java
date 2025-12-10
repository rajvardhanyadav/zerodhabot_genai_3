package com.tradingbot.repository;

import com.tradingbot.entity.OrderTimingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Order Timing entity operations.
 * Essential for HFT latency analysis.
 */
@Repository
public interface OrderTimingRepository extends JpaRepository<OrderTimingEntity, Long> {

    // Find by order ID
    Optional<OrderTimingEntity> findByOrderId(String orderId);

    // Find by execution ID
    List<OrderTimingEntity> findByExecutionIdOrderByOrderTimestamp(String executionId);

    // Find by user and date range
    List<OrderTimingEntity> findByUserIdAndOrderTimestampBetweenOrderByOrderTimestampDesc(
            String userId, LocalDateTime startTime, LocalDateTime endTime);

    // Get average latency for a user
    @Query("SELECT AVG(o.totalLatencyMs) FROM OrderTimingEntity o " +
           "WHERE o.userId = :userId AND o.orderTimestamp BETWEEN :startTime AND :endTime")
    Optional<Double> getAverageLatencyForDateRange(
            @Param("userId") String userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    // Get latency statistics
    @Query("SELECT MIN(o.totalLatencyMs), MAX(o.totalLatencyMs), AVG(o.totalLatencyMs) " +
           "FROM OrderTimingEntity o " +
           "WHERE o.userId = :userId AND o.orderTimestamp BETWEEN :startTime AND :endTime")
    List<Object[]> getLatencyStatsForDateRange(
            @Param("userId") String userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    // Get average slippage
    @Query("SELECT AVG(o.slippagePoints), AVG(o.slippagePercent) FROM OrderTimingEntity o " +
           "WHERE o.userId = :userId AND o.orderTimestamp BETWEEN :startTime AND :endTime")
    List<Object[]> getAverageSlippageForDateRange(
            @Param("userId") String userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    // Find high latency orders (for analysis)
    @Query("SELECT o FROM OrderTimingEntity o " +
           "WHERE o.userId = :userId AND o.totalLatencyMs > :threshold " +
           "ORDER BY o.totalLatencyMs DESC")
    List<OrderTimingEntity> findHighLatencyOrders(
            @Param("userId") String userId,
            @Param("threshold") Long thresholdMs);

    // Find orders with high slippage
    @Query("SELECT o FROM OrderTimingEntity o " +
           "WHERE o.userId = :userId AND ABS(o.slippagePercent) > :threshold " +
           "ORDER BY ABS(o.slippagePercent) DESC")
    List<OrderTimingEntity> findHighSlippageOrders(
            @Param("userId") String userId,
            @Param("threshold") java.math.BigDecimal thresholdPercent);

    // Get latency by order context
    @Query("SELECT o.orderContext, AVG(o.totalLatencyMs), COUNT(o) FROM OrderTimingEntity o " +
           "WHERE o.userId = :userId AND o.orderTimestamp BETWEEN :startTime AND :endTime " +
           "GROUP BY o.orderContext")
    List<Object[]> getLatencyByContext(
            @Param("userId") String userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    // Delete old timing data
    void deleteByOrderTimestampBefore(LocalDateTime cutoffTime);
}

