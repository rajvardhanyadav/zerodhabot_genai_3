package com.tradingbot.repository;

import com.tradingbot.entity.StrategyExecutionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Strategy Execution entity operations.
 */
@Repository
public interface StrategyExecutionRepository extends JpaRepository<StrategyExecutionEntity, Long> {

    // Find by execution ID
    Optional<StrategyExecutionEntity> findByExecutionId(String executionId);

    // Find by root execution ID (all executions in a restart chain)
    List<StrategyExecutionEntity> findByRootExecutionIdOrderByCreatedAt(String rootExecutionId);

    // Find by user and status
    List<StrategyExecutionEntity> findByUserIdAndStatusOrderByCreatedAtDesc(String userId, String status);

    // Find by user and date range
    List<StrategyExecutionEntity> findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            String userId, LocalDateTime startTime, LocalDateTime endTime);

    // Find by user
    List<StrategyExecutionEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    // Find active executions for a user
    @Query("SELECT s FROM StrategyExecutionEntity s WHERE s.userId = :userId " +
           "AND s.status IN ('EXECUTING', 'MONITORING') ORDER BY s.createdAt DESC")
    List<StrategyExecutionEntity> findActiveByUserId(@Param("userId") String userId);

    // Find by strategy type
    List<StrategyExecutionEntity> findByUserIdAndStrategyTypeOrderByCreatedAtDesc(String userId, String strategyType);

    // Find by trading mode
    List<StrategyExecutionEntity> findByUserIdAndTradingModeOrderByCreatedAtDesc(String userId, String tradingMode);

    // Find completed executions
    @Query("SELECT s FROM StrategyExecutionEntity s WHERE s.userId = :userId " +
           "AND s.status = 'COMPLETED' AND s.createdAt BETWEEN :startTime AND :endTime " +
           "ORDER BY s.createdAt DESC")
    List<StrategyExecutionEntity> findCompletedByUserIdAndDateRange(
            @Param("userId") String userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    // Count by status for a user
    @Query("SELECT s.status, COUNT(s) FROM StrategyExecutionEntity s WHERE s.userId = :userId " +
           "GROUP BY s.status")
    List<Object[]> countByStatusForUser(@Param("userId") String userId);

    // Aggregate P&L by strategy type
    @Query("SELECT s.strategyType, COUNT(s), SUM(s.realizedPnl) FROM StrategyExecutionEntity s " +
           "WHERE s.userId = :userId AND s.createdAt BETWEEN :startTime AND :endTime " +
           "GROUP BY s.strategyType")
    List<Object[]> aggregatePnlByStrategyType(
            @Param("userId") String userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    // Find latest N executions
    List<StrategyExecutionEntity> findTop20ByUserIdOrderByCreatedAtDesc(String userId);

    // Find by completion reason
    List<StrategyExecutionEntity> findByUserIdAndCompletionReasonOrderByCreatedAtDesc(
            String userId, String completionReason);

    // Delete old executions
    void deleteByCreatedAtBefore(LocalDateTime cutoffTime);
}

