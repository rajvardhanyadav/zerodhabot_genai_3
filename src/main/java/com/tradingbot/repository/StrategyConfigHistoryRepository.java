package com.tradingbot.repository;

import com.tradingbot.entity.StrategyConfigHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Strategy Config History entity operations.
 */
@Repository
public interface StrategyConfigHistoryRepository extends JpaRepository<StrategyConfigHistoryEntity, Long> {

    // Find by strategy name
    List<StrategyConfigHistoryEntity> findByStrategyNameOrderByChangedAtDesc(String strategyName);

    // Find by user
    List<StrategyConfigHistoryEntity> findByUserIdOrderByChangedAtDesc(String userId);

    // Find by user and strategy name
    List<StrategyConfigHistoryEntity> findByUserIdAndStrategyNameOrderByChangedAtDesc(
            String userId, String strategyName);

    // Find recent config changes
    List<StrategyConfigHistoryEntity> findTop50ByOrderByChangedAtDesc();

    // Find recent config changes by user
    List<StrategyConfigHistoryEntity> findTop50ByUserIdOrderByChangedAtDesc(String userId);

    // Find latest config for strategy
    @Query("SELECT s FROM StrategyConfigHistoryEntity s WHERE s.strategyName = :name " +
           "ORDER BY s.changedAt DESC")
    List<StrategyConfigHistoryEntity> findLatestByStrategyName(@Param("name") String strategyName);

    default Optional<StrategyConfigHistoryEntity> findLatestConfigByStrategyName(String strategyName) {
        List<StrategyConfigHistoryEntity> results = findLatestByStrategyName(strategyName);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    // Find by changed by
    List<StrategyConfigHistoryEntity> findByChangedByOrderByChangedAtDesc(String changedBy);

    // Find by timestamp range
    List<StrategyConfigHistoryEntity> findByChangedAtBetweenOrderByChangedAtDesc(
            LocalDateTime start, LocalDateTime end);

    // Delete old records
    void deleteByChangedAtBefore(LocalDateTime before);

    // Count changes per strategy
    @Query("SELECT s.strategyName, COUNT(s) FROM StrategyConfigHistoryEntity s " +
           "WHERE s.changedAt BETWEEN :start AND :end GROUP BY s.strategyName")
    List<Object[]> countChangesByStrategyForDateRange(
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}

