package com.tradingbot.repository;

import com.tradingbot.entity.MTMSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for MTM Snapshot entity operations.
 */
@Repository
public interface MTMSnapshotRepository extends JpaRepository<MTMSnapshotEntity, Long> {

    // Find by trading date
    List<MTMSnapshotEntity> findByTradingDateOrderByTimestamp(LocalDate tradingDate);

    // Find by user and trading date
    List<MTMSnapshotEntity> findByUserIdAndTradingDateOrderByTimestamp(String userId, LocalDate tradingDate);

    // Find by execution ID
    List<MTMSnapshotEntity> findByExecutionIdOrderByTimestamp(String executionId);

    // Find latest for user today
    @Query("SELECT m FROM MTMSnapshotEntity m WHERE m.userId = :userId AND m.tradingDate = :date " +
           "ORDER BY m.timestamp DESC")
    List<MTMSnapshotEntity> findLatestByUserIdAndDate(
            @Param("userId") String userId, @Param("date") LocalDate date);

    default Optional<MTMSnapshotEntity> findLatestSnapshotByUserIdAndDate(String userId, LocalDate date) {
        List<MTMSnapshotEntity> results = findLatestByUserIdAndDate(userId, date);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    // Find max MTM for date
    @Query("SELECT MAX(m.totalMTM) FROM MTMSnapshotEntity m WHERE m.userId = :userId AND m.tradingDate = :date")
    Optional<BigDecimal> findMaxMTMByUserIdAndDate(
            @Param("userId") String userId, @Param("date") LocalDate date);

    // Find min MTM for date
    @Query("SELECT MIN(m.totalMTM) FROM MTMSnapshotEntity m WHERE m.userId = :userId AND m.tradingDate = :date")
    Optional<BigDecimal> findMinMTMByUserIdAndDate(
            @Param("userId") String userId, @Param("date") LocalDate date);

    // Find by user and date range
    List<MTMSnapshotEntity> findByUserIdAndTradingDateBetweenOrderByTimestamp(
            String userId, LocalDate startDate, LocalDate endDate);

    // Count snapshots for date
    @Query("SELECT COUNT(m) FROM MTMSnapshotEntity m WHERE m.tradingDate = :date")
    long countByTradingDate(@Param("date") LocalDate date);

    // Delete old snapshots
    void deleteByTimestampBefore(LocalDateTime before);

    // Find recent snapshots for user
    List<MTMSnapshotEntity> findTop100ByUserIdOrderByTimestampDesc(String userId);

    // Aggregate statistics
    @Query("SELECT m.tradingDate, MAX(m.totalMTM), MIN(m.totalMTM), AVG(m.totalMTM) " +
           "FROM MTMSnapshotEntity m WHERE m.userId = :userId " +
           "AND m.tradingDate BETWEEN :startDate AND :endDate " +
           "GROUP BY m.tradingDate ORDER BY m.tradingDate")
    List<Object[]> getDailyMTMStatsByUserIdAndDateRange(
            @Param("userId") String userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);
}

