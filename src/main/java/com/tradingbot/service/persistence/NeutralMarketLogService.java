package com.tradingbot.service.persistence;

import com.tradingbot.config.PersistenceConfig;
import com.tradingbot.entity.NeutralMarketLogEntity;
import com.tradingbot.model.NeutralMarketResultV3;
import com.tradingbot.repository.NeutralMarketLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Persistence service for V3 neutral market detection evaluation logs.
 *
 * <p>Writes are async (on {@code persistenceExecutor}) to avoid blocking strategy threads.
 * Reads are synchronous for REST API queries.</p>
 *
 * @since 4.3
 * @see NeutralMarketLogEntity
 * @see NeutralMarketLogRepository
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NeutralMarketLogService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final NeutralMarketLogRepository neutralMarketLogRepository;
    private final PersistenceConfig persistenceConfig;

    // ==================== ASYNC WRITE ====================

    /**
     * Persist a V3 neutral market evaluation result asynchronously.
     * Called by {@code MarketStateUpdater} after each evaluation cycle.
     *
     * @param result       the V3 evaluation result (enriched with spot, vwap, elapsed, etc.)
     * @param instrument   instrument evaluated (e.g., "NIFTY")
     * @return CompletableFuture with the persisted entity
     */
    @Async("persistenceExecutor")
    @Transactional
    public CompletableFuture<NeutralMarketLogEntity> persistEvaluationAsync(
            NeutralMarketResultV3 result, String instrument) {

        if (!persistenceConfig.isEnabled()) {
            log.debug("Persistence disabled, skipping neutral market log write");
            return CompletableFuture.completedFuture(null);
        }

        try {
            NeutralMarketLogEntity entity = mapToEntity(result, instrument);
            NeutralMarketLogEntity saved = neutralMarketLogRepository.save(entity);
            log.debug("Persisted neutral market log: id={}, instrument={}, tradable={}, regime={}, score={}/{}",
                    saved.getId(), instrument, saved.getTradable(), saved.getRegime(),
                    saved.getRegimeScore(), saved.getFinalScore());
            return CompletableFuture.completedFuture(saved);
        } catch (Exception e) {
            log.error("Failed to persist neutral market log for {}: {}", instrument, e.getMessage(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    // ==================== QUERY METHODS ====================

    /**
     * Get all evaluation logs for a specific date (IST trading day).
     */
    public List<NeutralMarketLogEntity> getLogsByDate(LocalDate date) {
        return neutralMarketLogRepository.findByTradingDateOrderByEvaluatedAtDesc(date);
    }

    /**
     * Get all evaluation logs within a date range (inclusive).
     */
    public List<NeutralMarketLogEntity> getLogsByDateRange(LocalDate from, LocalDate to) {
        return neutralMarketLogRepository.findByTradingDateBetweenOrderByEvaluatedAtDesc(from, to);
    }

    /**
     * Get evaluation logs for a specific instrument on a date.
     */
    public List<NeutralMarketLogEntity> getLogsByInstrumentAndDate(String instrument, LocalDate date) {
        return neutralMarketLogRepository.findByInstrumentAndTradingDateOrderByEvaluatedAtDesc(
                instrument.toUpperCase(), date);
    }

    /**
     * Get aggregated summary statistics for a date.
     */
    public Map<String, Object> getSummaryByDate(LocalDate date) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("date", date.toString());

        long totalEvals = neutralMarketLogRepository.countByTradingDate(date);
        long tradableEvals = neutralMarketLogRepository.countByTradingDateAndTradable(date, true);
        long skippedEvals = totalEvals - tradableEvals;

        summary.put("totalEvaluations", totalEvals);
        summary.put("tradableCount", tradableEvals);
        summary.put("skippedCount", skippedEvals);
        summary.put("tradablePercentage", totalEvals > 0
                ? Math.round((double) tradableEvals / totalEvals * 10000.0) / 100.0 : 0.0);

        // Average scores
        Double avgRegime = neutralMarketLogRepository.findAvgRegimeScoreByDate(date);
        Double avgMicro = neutralMarketLogRepository.findAvgMicroScoreByDate(date);
        Double avgConfidence = neutralMarketLogRepository.findAvgConfidenceByDate(date);
        Double avgDurationMs = neutralMarketLogRepository.findAvgEvaluationDurationByDate(date);

        summary.put("avgRegimeScore", avgRegime != null ? Math.round(avgRegime * 100.0) / 100.0 : null);
        summary.put("avgMicroScore", avgMicro != null ? Math.round(avgMicro * 100.0) / 100.0 : null);
        summary.put("avgConfidence", avgConfidence != null ? Math.round(avgConfidence * 1000.0) / 1000.0 : null);
        summary.put("avgEvaluationDurationMs", avgDurationMs != null ? Math.round(avgDurationMs * 10.0) / 10.0 : null);

        // Regime distribution
        List<Object[]> regimeDist = neutralMarketLogRepository.findRegimeDistributionByDate(date);
        Map<String, Long> regimeMap = new LinkedHashMap<>();
        for (Object[] row : regimeDist) {
            regimeMap.put((String) row[0], (Long) row[1]);
        }
        summary.put("regimeDistribution", regimeMap);

        // Veto reason distribution
        List<Object[]> vetoDist = neutralMarketLogRepository.findVetoReasonDistributionByDate(date);
        Map<String, Long> vetoMap = new LinkedHashMap<>();
        for (Object[] row : vetoDist) {
            vetoMap.put((String) row[0], (Long) row[1]);
        }
        summary.put("vetoReasonDistribution", vetoMap);

        // Signal pass rates
        List<Object[]> signalCounts = neutralMarketLogRepository.findSignalPassCountsByDate(date);
        if (!signalCounts.isEmpty()) {
            Object[] row = signalCounts.get(0);
            long total = ((Number) row[9]).longValue();
            if (total > 0) {
                Map<String, String> signalRates = new LinkedHashMap<>();
                String[] signalNames = {
                        "VWAP_PROXIMITY", "RANGE_COMPRESSION", "OSCILLATION", "ADX_TREND",
                        "GAMMA_PIN", "NET_DISPLACEMENT",
                        "MICRO_VWAP_PULLBACK", "MICRO_HF_OSCILLATION", "MICRO_RANGE_STABILITY"
                };
                for (int i = 0; i < 9; i++) {
                    long passCount = row[i] != null ? ((Number) row[i]).longValue() : 0;
                    double pct = Math.round((double) passCount / total * 10000.0) / 100.0;
                    signalRates.put(signalNames[i], passCount + "/" + total + " (" + pct + "%)");
                }
                summary.put("signalPassRates", signalRates);
            }
        }

        return summary;
    }

    // ==================== MAPPING ====================

    /**
     * Map a V3 evaluation result to the persistence entity.
     * Uses enrichment fields from the result when available.
     */
    private NeutralMarketLogEntity mapToEntity(NeutralMarketResultV3 result, String instrument) {
        Map<String, Boolean> signals = result.getSignals();

        LocalDateTime evaluatedAtLdt = LocalDateTime.ofInstant(
                result.getEvaluatedAt(), IST);
        LocalDate tradingDate = evaluatedAtLdt.toLocalDate();

        return NeutralMarketLogEntity.builder()
                .instrument(instrument.toUpperCase())
                .evaluatedAt(evaluatedAtLdt)
                .tradingDate(tradingDate)
                .spotPrice(result.getSpotPrice())
                .vwapValue(result.getVwapValue())
                // Final decision
                .tradable(result.isTradable())
                .regime(result.getRegime() != null ? result.getRegime().name() : "UNKNOWN")
                .breakoutRisk(result.getBreakoutRisk() != null ? result.getBreakoutRisk().name() : "LOW")
                .vetoReason(result.getVetoReason())
                // Scores
                .regimeScore(result.getRegimeScore())
                .microScore(result.getMicroScore())
                .finalScore(result.getFinalScore())
                .confidence(result.getConfidence())
                .timeAdjustment(result.getTimeAdjustment())
                .microTradable(result.isMicroTradable())
                // Regime signals (R1–R5)
                .vwapProximityPassed(getSignal(signals, "VWAP_PROXIMITY"))
                .vwapDeviation(result.getVwapDeviation())
                .rangeCompressionPassed(getSignal(signals, "RANGE_COMPRESSION"))
                .rangeFraction(result.getRangeFraction())
                .oscillationPassed(getSignal(signals, "OSCILLATION"))
                .oscillationReversals(result.getOscillationReversals())
                .adxPassed(getSignal(signals, "ADX_TREND"))
                .adxValue(result.getAdxValue())
                .gammaPinPassed(getSignal(signals, "GAMMA_PIN"))
                .netDisplacementPassed(getSignal(signals, "NET_DISPLACEMENT"))
                .netDisplacement(result.getNetDisplacement())
                .expiryDay(result.isExpiryDay())
                // Microstructure signals (M1–M3)
                .microVwapPullbackPassed(getSignal(signals, "MICRO_VWAP_PULLBACK"))
                .microHfOscillationPassed(getSignal(signals, "MICRO_HF_OSCILLATION"))
                .microRangeStabilityPassed(getSignal(signals, "MICRO_RANGE_STABILITY"))
                // Breakout / veto gate signals
                .breakoutRiskLow(getSignal(signals, "BREAKOUT_RISK_LOW"))
                .excessiveRangeSafe(getSignal(signals, "EXCESSIVE_RANGE_SAFE"))
                // Summary & performance
                .summary(result.getSummary())
                .evaluationDurationMs(result.getEvaluationDurationMs())
                .build();
    }

    /**
     * Safe signal lookup — returns false if key is missing.
     */
    private boolean getSignal(Map<String, Boolean> signals, String key) {
        Boolean val = signals.get(key);
        return val != null && val;
    }
}

