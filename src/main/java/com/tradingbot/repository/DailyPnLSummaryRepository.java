package com.tradingbot.repository;

import com.tradingbot.entity.DailyPnLSummaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Daily P&L Summary entity operations.
 */
@Repository
public interface DailyPnLSummaryRepository extends JpaRepository<DailyPnLSummaryEntity, Long> {

    // Find by user and date
    Optional<DailyPnLSummaryEntity> findByUserIdAndTradingDateAndTradingMode(
            String userId, LocalDate tradingDate, String tradingMode);

    // Find by user and date range
    List<DailyPnLSummaryEntity> findByUserIdAndTradingDateBetweenOrderByTradingDateDesc(
            String userId, LocalDate startDate, LocalDate endDate);

    // Find by user and trading mode for date range
    List<DailyPnLSummaryEntity> findByUserIdAndTradingModeAndTradingDateBetweenOrderByTradingDateDesc(
            String userId, String tradingMode, LocalDate startDate, LocalDate endDate);

    // Find all by user
    List<DailyPnLSummaryEntity> findByUserIdOrderByTradingDateDesc(String userId);

    // Aggregate P&L for date range
    @Query("SELECT SUM(d.netPnl) FROM DailyPnLSummaryEntity d " +
           "WHERE d.userId = :userId AND d.tradingDate BETWEEN :startDate AND :endDate")
    Optional<BigDecimal> sumNetPnlForDateRange(
            @Param("userId") String userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    // Get total trades for date range
    @Query("SELECT SUM(d.totalTrades) FROM DailyPnLSummaryEntity d " +
           "WHERE d.userId = :userId AND d.tradingDate BETWEEN :startDate AND :endDate")
    Optional<Long> sumTotalTradesForDateRange(
            @Param("userId") String userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    // Get overall win rate for date range
    @Query("SELECT SUM(d.winningTrades), SUM(d.totalTrades) FROM DailyPnLSummaryEntity d " +
           "WHERE d.userId = :userId AND d.tradingDate BETWEEN :startDate AND :endDate")
    List<Object[]> getWinRateDataForDateRange(
            @Param("userId") String userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    // Find best trading day
    @Query("SELECT d FROM DailyPnLSummaryEntity d " +
           "WHERE d.userId = :userId AND d.tradingDate BETWEEN :startDate AND :endDate " +
           "ORDER BY d.netPnl DESC LIMIT 1")
    Optional<DailyPnLSummaryEntity> findBestDayForDateRange(
            @Param("userId") String userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    // Find worst trading day
    @Query("SELECT d FROM DailyPnLSummaryEntity d " +
           "WHERE d.userId = :userId AND d.tradingDate BETWEEN :startDate AND :endDate " +
           "ORDER BY d.netPnl ASC LIMIT 1")
    Optional<DailyPnLSummaryEntity> findWorstDayForDateRange(
            @Param("userId") String userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    // Get monthly summary
    @Query("SELECT FUNCTION('YEAR', d.tradingDate), FUNCTION('MONTH', d.tradingDate), " +
           "SUM(d.netPnl), SUM(d.totalTrades), SUM(d.winningTrades), COUNT(d) " +
           "FROM DailyPnLSummaryEntity d " +
           "WHERE d.userId = :userId AND d.tradingDate BETWEEN :startDate AND :endDate " +
           "GROUP BY FUNCTION('YEAR', d.tradingDate), FUNCTION('MONTH', d.tradingDate) " +
           "ORDER BY FUNCTION('YEAR', d.tradingDate) DESC, FUNCTION('MONTH', d.tradingDate) DESC")
    List<Object[]> getMonthlySummary(
            @Param("userId") String userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    // Delete old summaries
    void deleteByTradingDateBefore(LocalDate cutoffDate);
}

