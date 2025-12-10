package com.tradingbot.repository;

import com.tradingbot.entity.TradeEntity;
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
 * Repository for Trade entity operations.
 * Provides methods for querying trade history with various filters.
 */
@Repository
public interface TradeRepository extends JpaRepository<TradeEntity, Long> {

    // Find by user and date range
    List<TradeEntity> findByUserIdAndTradingDateBetweenOrderByEntryTimestampDesc(
            String userId, LocalDate startDate, LocalDate endDate);

    // Find by user and specific date
    List<TradeEntity> findByUserIdAndTradingDateOrderByEntryTimestampDesc(String userId, LocalDate tradingDate);

    // Find by execution ID
    List<TradeEntity> findByExecutionIdOrderByEntryTimestamp(String executionId);

    // Find by order ID
    Optional<TradeEntity> findByOrderId(String orderId);

    // Find by user and trading mode
    List<TradeEntity> findByUserIdAndTradingModeAndTradingDateBetweenOrderByEntryTimestampDesc(
            String userId, String tradingMode, LocalDate startDate, LocalDate endDate);

    // Find open trades for a user
    List<TradeEntity> findByUserIdAndStatusOrderByEntryTimestampDesc(String userId, String status);

    // Find by symbol and date range
    List<TradeEntity> findByUserIdAndTradingSymbolAndTradingDateBetween(
            String userId, String tradingSymbol, LocalDate startDate, LocalDate endDate);

    // Aggregate queries for daily summary
    @Query("SELECT SUM(t.realizedPnl) FROM TradeEntity t WHERE t.userId = :userId AND t.tradingDate = :date")
    Optional<BigDecimal> sumRealizedPnlByUserIdAndDate(@Param("userId") String userId, @Param("date") LocalDate date);

    @Query("SELECT SUM(t.totalCharges) FROM TradeEntity t WHERE t.userId = :userId AND t.tradingDate = :date")
    Optional<BigDecimal> sumChargesByUserIdAndDate(@Param("userId") String userId, @Param("date") LocalDate date);

    @Query("SELECT COUNT(t) FROM TradeEntity t WHERE t.userId = :userId AND t.tradingDate = :date")
    Long countByUserIdAndDate(@Param("userId") String userId, @Param("date") LocalDate date);

    @Query("SELECT COUNT(t) FROM TradeEntity t WHERE t.userId = :userId AND t.tradingDate = :date AND t.realizedPnl > 0")
    Long countWinningTradesByUserIdAndDate(@Param("userId") String userId, @Param("date") LocalDate date);

    @Query("SELECT COUNT(t) FROM TradeEntity t WHERE t.userId = :userId AND t.tradingDate = :date AND t.realizedPnl < 0")
    Long countLosingTradesByUserIdAndDate(@Param("userId") String userId, @Param("date") LocalDate date);

    // Find latest trades for a user
    List<TradeEntity> findTop10ByUserIdOrderByEntryTimestampDesc(String userId);

    // Find trades by timestamp range
    List<TradeEntity> findByUserIdAndEntryTimestampBetweenOrderByEntryTimestampDesc(
            String userId, LocalDateTime startTime, LocalDateTime endTime);

    // Instrument analysis
    @Query("SELECT t.tradingSymbol, COUNT(t), SUM(t.realizedPnl) FROM TradeEntity t " +
           "WHERE t.userId = :userId AND t.tradingDate BETWEEN :startDate AND :endDate " +
           "GROUP BY t.tradingSymbol ORDER BY SUM(t.realizedPnl) DESC")
    List<Object[]> findPnlBySymbolForUserAndDateRange(
            @Param("userId") String userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    // Delete old trades (for cleanup job)
    void deleteByTradingDateBefore(LocalDate cutoffDate);
}

