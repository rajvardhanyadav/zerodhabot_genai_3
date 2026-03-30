package com.tradingbot.repository;

import com.tradingbot.entity.NeutralMarketLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for neutral market detection log persistence and querying.
 *
 * <p>Supports date-based analysis queries for retrospective evaluation of
 * the V3 neutral market detector accuracy and signal patterns.</p>
 *
 * @since 4.3
 */
@Repository
public interface NeutralMarketLogRepository extends JpaRepository<NeutralMarketLogEntity, Long> {

    // ==================== DATE-BASED QUERIES ====================

    /** All evaluations for a specific date, ordered chronologically (most recent first). */
    List<NeutralMarketLogEntity> findByTradingDateOrderByEvaluatedAtDesc(LocalDate tradingDate);

    /** All evaluations within a date range, ordered chronologically (most recent first). */
    List<NeutralMarketLogEntity> findByTradingDateBetweenOrderByEvaluatedAtDesc(
            LocalDate from, LocalDate to);

    /** All evaluations for a specific instrument on a specific date. */
    List<NeutralMarketLogEntity> findByInstrumentAndTradingDateOrderByEvaluatedAtDesc(
            String instrument, LocalDate tradingDate);

    /** All evaluations for a specific instrument within a date range. */
    List<NeutralMarketLogEntity> findByInstrumentAndTradingDateBetweenOrderByEvaluatedAtDesc(
            String instrument, LocalDate from, LocalDate to);

    // ==================== SUMMARY / AGGREGATE QUERIES ====================

    /** Count total evaluations for a date. */
    long countByTradingDate(LocalDate tradingDate);

    /** Count tradable evaluations for a date. */
    long countByTradingDateAndTradable(LocalDate tradingDate, boolean tradable);

    /** Average regime score for a date. */
    @Query("SELECT AVG(n.regimeScore) FROM NeutralMarketLogEntity n WHERE n.tradingDate = :date")
    Double findAvgRegimeScoreByDate(@Param("date") LocalDate date);

    /** Average micro score for a date. */
    @Query("SELECT AVG(n.microScore) FROM NeutralMarketLogEntity n WHERE n.tradingDate = :date")
    Double findAvgMicroScoreByDate(@Param("date") LocalDate date);

    /** Average confidence for a date. */
    @Query("SELECT AVG(n.confidence) FROM NeutralMarketLogEntity n WHERE n.tradingDate = :date")
    Double findAvgConfidenceByDate(@Param("date") LocalDate date);

    /** Average evaluation duration for a date. */
    @Query("SELECT AVG(n.evaluationDurationMs) FROM NeutralMarketLogEntity n WHERE n.tradingDate = :date")
    Double findAvgEvaluationDurationByDate(@Param("date") LocalDate date);

    /** Regime distribution for a date: count per regime type. */
    @Query("SELECT n.regime, COUNT(n) FROM NeutralMarketLogEntity n " +
           "WHERE n.tradingDate = :date GROUP BY n.regime")
    List<Object[]> findRegimeDistributionByDate(@Param("date") LocalDate date);

    /** Veto reason distribution for a date (only non-null veto reasons). */
    @Query("SELECT n.vetoReason, COUNT(n) FROM NeutralMarketLogEntity n " +
           "WHERE n.tradingDate = :date AND n.vetoReason IS NOT NULL GROUP BY n.vetoReason")
    List<Object[]> findVetoReasonDistributionByDate(@Param("date") LocalDate date);

    /** Signal pass rates for a date — returns counts for each signal. */
    @Query("SELECT " +
           "SUM(CASE WHEN n.vwapProximityPassed = true THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN n.rangeCompressionPassed = true THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN n.oscillationPassed = true THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN n.adxPassed = true THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN n.gammaPinPassed = true THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN n.netDisplacementPassed = true THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN n.microVwapPullbackPassed = true THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN n.microHfOscillationPassed = true THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN n.microRangeStabilityPassed = true THEN 1 ELSE 0 END), " +
           "COUNT(n) " +
           "FROM NeutralMarketLogEntity n WHERE n.tradingDate = :date")
    List<Object[]> findSignalPassCountsByDate(@Param("date") LocalDate date);

    // ==================== CLEANUP ====================

    /** Delete logs older than the given timestamp (for retention policy). */
    void deleteByEvaluatedAtBefore(LocalDateTime before);
}

