package com.tradingbot.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the Neutral Market Detection Engine.
 *
 * Controls the multi-signal neutrality filter that determines whether ATM straddle
 * placement should proceed. The engine evaluates VWAP deviation, ADX trend strength,
 * Gamma Pin (max OI strike proximity), range compression, and dual premium decay.
 *
 * Thread-safety: Immutable after Spring initialization (property binding). Safe for
 * concurrent access from strategy threads.
 *
 * @since 4.2
 */
@Configuration
@ConfigurationProperties(prefix = "neutral-market")
@Data
@Slf4j
public class NeutralMarketConfig {

    @PostConstruct
    void validate() {
        if (enabled && premiumSnapshotMinIntervalMs >= cacheTtlMs) {
            log.warn("MISCONFIGURED: premium-snapshot-min-interval-ms ({}) >= cache-ttl-ms ({}). " +
                            "Premium decay signal will almost never pass due to timing race. " +
                            "Set premium-snapshot-min-interval-ms < cache-ttl-ms (recommend {}ms).",
                    premiumSnapshotMinIntervalMs, cacheTtlMs, cacheTtlMs - 5000);
        }
        if (enabled) {
            log.info("Neutral market config: enabled={}, minimumScore={}, cacheTtlMs={}, " +
                            "vwapThreshold={}, adxThreshold={}, gammaPinThreshold={}, " +
                            "rangeThreshold={}, premiumSnapshotIntervalMs={}, allowOnUnavailable={}",
                    enabled, minimumScore, cacheTtlMs,
                    vwapDeviationThreshold, adxThreshold, gammaPinThreshold,
                    rangeCompressionThreshold, premiumSnapshotMinIntervalMs, allowOnDataUnavailable);
        }
    }

    /**
     * Master switch to enable/disable the neutral market filter.
     * When disabled, straddle placement proceeds unconditionally.
     * Default: false (disabled — opt-in)
     */
    private boolean enabled = false;

    /**
     * Minimum composite score (0–10) required to consider the market neutral.
     * Each signal contributes +2 to the score. With 5 signals, max score = 10.
     * Default: 7 (requires at least 4 of 5 signals to pass)
     */
    private int minimumScore = 7;

    // ==================== VWAP DEVIATION ====================

    /**
     * Maximum allowed percentage deviation between spot price and VWAP.
     * Expressed as a decimal fraction (e.g., 0.0015 = 0.15%).
     * Score +2 if abs(price - vwap) / vwap &lt; this threshold.
     * Default: 0.0015 (0.15%)
     */
    private double vwapDeviationThreshold = 0.0015;

    /**
     * Number of 1-minute candles to use for VWAP calculation.
     * More candles give a smoother VWAP but are less responsive to recent changes.
     * Default: 15
     */
    private int vwapCandleCount = 15;

    // ==================== ADX TREND STRENGTH ====================

    /**
     * ADX threshold below which the market is considered range-bound.
     * ADX &lt; this value means no strong trend, i.e. neutral market.
     * Score +2 if ADX &lt; this threshold.
     * Default: 18
     */
    private double adxThreshold = 18.0;

    /**
     * ADX smoothing period (Wilder's method).
     * Standard value is 14. Higher values are smoother but slower to react.
     * Default: 14
     */
    private int adxPeriod = 14;

    /**
     * Candle interval for ADX computation.
     * Using 3-minute candles provides stable ADX with 14-period lookback (~42 min window).
     * 1-minute candles would require 50+ candles and produce noisy, unreliable results.
     * Default: "3minute"
     */
    private String adxCandleInterval = "3minute";

    /**
     * Number of candles to fetch for ADX computation.
     * Must be &gt;= 2 * adxPeriod + buffer for Wilder smoothing warmup.
     * Default: 50 (gives ~22 valid ADX readings on 3-minute candles over ~2.5 hours)
     */
    private int adxCandleCount = 50;

    /**
     * Enable the optional ADX-falling check: ADX must be falling for last 3 readings.
     * When enabled, this is an additional condition on top of ADX &lt; threshold.
     * Default: false
     */
    private boolean adxFallingCheckEnabled = false;

    // ==================== GAMMA PIN (MAX OI STRIKE) ====================

    /**
     * Maximum allowed percentage distance between spot price and the max-OI strike.
     * Expressed as a decimal fraction (e.g., 0.002 = 0.2%).
     * Score +2 if abs(spotPrice - maxOIStrike) / spotPrice &lt; this threshold.
     * Default: 0.002 (0.2%)
     */
    private double gammaPinThreshold = 0.002;

    /**
     * Number of strikes on each side of ATM to check for OI.
     * Total strikes checked = 2 * strikesAroundAtm + 1.
     * Default: 10
     */
    private int strikesAroundAtm = 10;

    // ==================== RANGE COMPRESSION ====================

    /**
     * Maximum allowed price range (highestHigh - lowestLow) as a fraction of spot price.
     * Expressed as a decimal fraction (e.g., 0.0025 = 0.25%).
     * Score +2 if range / spotPrice &lt; this threshold.
     * Default: 0.0025 (0.25%)
     */
    private double rangeCompressionThreshold = 0.0025;

    /**
     * Number of recent 1-minute candles to use for range compression check.
     * Default: 5
     */
    private int rangeCompressionCandles = 5;

    // ==================== DUAL PREMIUM DECAY ====================

    /**
     * Minimum time (ms) between premium snapshots for decay comparison.
     * Prevents false positives from comparing snapshots taken too close together.
     * MUST be less than cacheTtlMs to avoid timing race where cache expiry and
     * snapshot interval align, causing the premium decay signal to perpetually skip.
     * Default: 25000 (25 seconds — gives 5s headroom with 30s cache TTL)
     */
    private long premiumSnapshotMinIntervalMs = 25000;

    // ==================== CACHING ====================

    /**
     * Cache TTL for the composite neutral market result in milliseconds.
     * Prevents excessive API calls by caching the full evaluation result.
     * Default: 30000 (30 seconds)
     */
    private long cacheTtlMs = 30000;

    /**
     * Fail-safe behavior when market data is unavailable.
     * When true, allows trade if data fetch fails.
     * When false, blocks trade on data unavailability.
     * Default: true (allow trade — don't block on transient API failures)
     */
    private boolean allowOnDataUnavailable = true;
}

