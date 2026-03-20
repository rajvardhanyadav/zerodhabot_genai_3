package com.tradingbot.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the 3-layer Neutral Market Detection Engine V3.
 *
 * <p>Controls the regime layer, microstructure layer, and breakout risk layer that
 * together determine whether an ATM straddle entry is both safe and well-timed.
 * All thresholds are configurable to allow field-tuning without code changes.</p>
 *
 * <h2>Thread Safety</h2>
 * Immutable after Spring initialization (property binding). Safe for concurrent
 * access from strategy threads and the scheduled evaluation cycle.
 *
 * @since 6.0
 * @see NeutralMarketDetectorServiceV3 (in com.tradingbot.service.strategy)
 */
@Configuration
@ConfigurationProperties(prefix = "neutral-market-v3")
@Data
@Slf4j
public class NeutralMarketV3Config {

    @PostConstruct
    void validate() {
        if (enabled) {
            log.info("NeutralMarketV3 config loaded: enabled={}, cacheTtlMs={}, " +
                            "regimeStrongThreshold={}, regimeWeakThreshold={}, " +
                            "timeAdaptation={}, breakoutTightRange={}",
                    enabled, cacheTtlMs,
                    regimeStrongNeutralThreshold, regimeWeakNeutralThreshold,
                    timeBasedAdaptationEnabled, breakoutTightRangeThreshold);
        }
    }

    // ==================== MASTER SWITCH ====================

    /** Enable/disable V3 neutral market detector. Default: true */
    private boolean enabled = true;

    /** Fail-safe: allow trade when data is unavailable. Default: true */
    private boolean allowOnDataUnavailable = true;

    // ==================== CACHING ====================

    /** Cache TTL for composite result in milliseconds. Default: 15000 (15s) */
    private long cacheTtlMs = 15000;

    // ==================== REGIME LAYER: WEIGHTS ====================

    /** Weight for VWAP Proximity signal. Default: 3 (highest — core mean-reversion signal) */
    private int weightVwapProximity = 3;

    /** Weight for Range Compression signal. Default: 2 */
    private int weightRangeCompression = 2;

    /** Weight for Price Oscillation signal. Default: 2 */
    private int weightOscillation = 2;

    /** Weight for ADX Trend Strength signal. Default: 1 (confirmatory, lagging) */
    private int weightAdx = 1;

    /** Weight for Gamma Pin signal (expiry day only). Default: 1 */
    private int weightGammaPin = 1;

    // ==================== REGIME LAYER: THRESHOLDS ====================

    /** VWAP proximity: max deviation from VWAP as fraction. Default: 0.002 (0.2%) */
    private double vwapProximityThreshold = 0.002;

    /**
     * Number of 1-min candles for VWAP computation (SMA proxy for index).
     * Default: 15
     */
    private int vwapCandleCount = 15;

    /** Range compression: max (high-low)/price over last N candles. Default: 0.0035 (0.35%) */
    private double rangeCompressionThreshold = 0.0035;

    /** Number of 1-min candles for range compression check. Default: 10 */
    private int rangeCompressionCandles = 10;

    /** Price oscillation: number of 1-min candles to check direction flips. Default: 10 */
    private int oscillationCandleCount = 10;

    /** Minimum direction reversals to pass oscillation signal. Default: 4 */
    private int oscillationMinReversals = 4;

    /** ADX threshold: market is ranging if ADX < this. Default: 20.0 */
    private double adxThreshold = 20.0;

    /** ADX Wilder smoothing period. Default: 7 (short-term intraday) */
    private int adxPeriod = 7;

    /** Candle interval for ADX. Default: "minute" */
    private String adxCandleInterval = "minute";

    /** Number of candles to fetch for ADX. Default: 30 */
    private int adxCandleCount = 30;

    /** Gamma pin: max distance from max-OI strike as fraction. Default: 0.002 (0.2%) */
    private double gammaPinThreshold = 0.002;

    /** Number of strikes each side of ATM to scan for OI. Default: 5 */
    private int strikesAroundAtm = 5;

    // ==================== REGIME LAYER: CLASSIFICATION ====================

    /** Regime score >= this → STRONG_NEUTRAL. Default: 6 */
    private int regimeStrongNeutralThreshold = 6;

    /** Regime score >= this → WEAK_NEUTRAL. Default: 4 */
    private int regimeWeakNeutralThreshold = 4;

    /** Minimum regime score for micro-neutral override (allows entry when micro signals are very strong). Default: 3 */
    private int microNeutralOverrideRegimeThreshold = 3;

    /** Minimum micro score for micro-neutral override. Default: 3 */
    private int microNeutralOverrideMicroThreshold = 3;

    // ==================== MICROSTRUCTURE LAYER: WEIGHTS ====================

    /** Weight for VWAP Pullback Momentum micro-signal. Default: 2 */
    private int weightMicroVwapPullback = 2;

    /** Weight for High-Frequency Oscillation micro-signal. Default: 2 */
    private int weightMicroOscillation = 2;

    /** Weight for Micro Range Stability micro-signal. Default: 1 */
    private int weightMicroRangeStability = 1;

    // ==================== MICROSTRUCTURE LAYER: THRESHOLDS ====================

    /**
     * VWAP Pullback: minimum deviation from VWAP to qualify as "away".
     * As fraction of VWAP. Default: 0.0015 (0.15%)
     */
    private double microVwapPullbackDeviationThreshold = 0.0015;

    /** Number of candles to check for VWAP pullback reversal. Default: 5 */
    private int microVwapPullbackCandles = 5;

    /** Number of candles for slope computation (last N moving toward VWAP). Default: 3 */
    private int microVwapPullbackSlopeCandles = 3;

    /**
     * HF Oscillation: number of recent candles for micro oscillation check.
     * Shorter window than regime oscillation for immediacy. Default: 8
     */
    private int microOscillationCandles = 8;

    /** Minimum direction flips in micro oscillation window. Default: 4 */
    private int microOscillationMinFlips = 4;

    /**
     * Maximum average absolute move per candle (as fraction of price) to confirm
     * oscillation is small-amplitude. Default: 0.001 (0.1%)
     */
    private double microOscillationMaxAvgMove = 0.001;

    /** Number of candles for micro range stability check. Default: 5 */
    private int microRangeCandles = 5;

    /** Max (high-low)/price for micro range stability to pass. Default: 0.001 (0.1%) */
    private double microRangeThreshold = 0.001;

    // ==================== BREAKOUT RISK LAYER ====================

    /**
     * Range tightness threshold: if range/price < this, consolidation is "tight".
     * Tight range is a precondition for breakout. Default: 0.0015 (0.15%)
     */
    private double breakoutTightRangeThreshold = 0.0015;

    /** Number of candles for breakout range analysis. Default: 10 */
    private int breakoutRangeCandles = 10;

    /**
     * Edge proximity: fraction of range from high/low that counts as "near edge".
     * Default: 0.2 (within 20% of the range from the edge)
     */
    private double breakoutEdgeProximityPct = 0.2;

    /**
     * Minimum consecutive same-direction candles at the end to detect building momentum.
     * Default: 3
     */
    private int breakoutMomentumCandles = 3;

    // ==================== TIME-BASED ADAPTATION ====================

    /**
     * Enable time-based score adjustment:
     * 09:15–10:00 → −1 (stricter, opening volatility)
     * 10:00–13:30 → 0 (normal)
     * 13:30–15:00 → +1 (relaxed, theta decay accelerates)
     * Default: true
     */
    private boolean timeBasedAdaptationEnabled = true;
}


