package com.tradingbot.repository;

import com.tradingbot.entity.AlertHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for Alert History entity operations.
 */
@Repository
public interface AlertHistoryRepository extends JpaRepository<AlertHistoryEntity, Long> {

    // Find by timestamp range
    List<AlertHistoryEntity> findByTimestampBetweenOrderByTimestampDesc(
            LocalDateTime start, LocalDateTime end);

    // Find by user and timestamp range
    List<AlertHistoryEntity> findByUserIdAndTimestampBetweenOrderByTimestampDesc(
            String userId, LocalDateTime start, LocalDateTime end);

    // Find by alert type and timestamp range
    List<AlertHistoryEntity> findByAlertTypeAndTimestampBetweenOrderByTimestampDesc(
            String alertType, LocalDateTime start, LocalDateTime end);

    // Find by strategy name
    List<AlertHistoryEntity> findByStrategyNameOrderByTimestampDesc(String strategyName);

    // Find recent alerts
    List<AlertHistoryEntity> findTop100ByOrderByTimestampDesc();

    // Find recent alerts by user
    List<AlertHistoryEntity> findTop100ByUserIdOrderByTimestampDesc(String userId);

    // Count alerts by type since a time
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.alertType = :type AND a.timestamp >= :since")
    long countByTypeAfter(@Param("type") String alertType, @Param("since") LocalDateTime since);

    // Count alerts by severity since a time
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.severity = :severity AND a.timestamp >= :since")
    long countBySeverityAfter(@Param("severity") String severity, @Param("since") LocalDateTime since);

    // Find by severity and timestamp
    List<AlertHistoryEntity> findBySeverityAndTimestampAfterOrderByTimestampDesc(
            String severity, LocalDateTime since);

    // Find failed telegram sends
    List<AlertHistoryEntity> findByTelegramSentFalseOrderByTimestampDesc();

    // Delete old alerts
    void deleteByTimestampBefore(LocalDateTime before);

    // Statistics by alert type
    @Query("SELECT a.alertType, COUNT(a) FROM AlertHistoryEntity a " +
           "WHERE a.timestamp BETWEEN :start AND :end GROUP BY a.alertType")
    List<Object[]> countByAlertTypeForDateRange(
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}

