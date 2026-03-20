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
                            "rangeThreshold={}, premiumSnapshotIntervalMs={}, premiumMinDecayPct={}, " +
                            "premiumMaxSnapshotAgeMs={}, allowOnUnavailable={}",
                    enabled, minimumScore, cacheTtlMs,
                    vwapDeviationThreshold, adxThreshold, gammaPinThreshold,
                    rangeCompressionThreshold, premiumSnapshotMinIntervalMs, premiumMinDecayPct,
                    premiumMaxSnapshotAgeMs, allowOnDataUnavailable);
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

    /**
     * Tighter minimum score required on expiry day (0–10).
     * On expiry day, gamma is elevated and only strongly pinned markets justify entry.
     * Default: 8 (requires at least 4 of 5 signals, with strong confidence)
     */
    private int expiryDayMinimumScore = 8;

    /**
     * Tighter range compression threshold on expiry day.
     * Expressed as a decimal fraction (e.g., 0.002 = 0.2%).
     * On expiry day, the market should be in an even tighter range to justify selling.
     * Default: 0.002 (0.2% = ~48 points at NIFTY 24000)
     */
    private double expiryDayRangeThreshold = 0.002;

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

    /**
     * Minimum percentage decay required for both CE and PE premiums to pass the signal.
     * Prevents trivially small decays (e.g., ₹0.05 on a ₹120 premium) from triggering a pass.
     * Expressed as a percentage (e.g., 0.5 = 0.5%).
     * Default: 0.5 (0.5% — for NIFTY ATM premiums of ~₹100–200, this is ₹0.50–₹1.00)
     */
    private double premiumMinDecayPct = 0.5;

    /**
     * Maximum age (ms) of the previous premium snapshot before it is discarded.
     * If the previous snapshot is older than this, the comparison is skipped and the
     * snapshot is replaced with the current reading (treated as a fresh baseline).
     * Prevents stale comparisons after cache clears, restarts, or prolonged data gaps.
     * Default: 120000 (2 minutes)
     */
    private long premiumMaxSnapshotAgeMs = 120000;

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

    // ==================== V2: PRICE OSCILLATION SIGNAL ====================

    /**
     * Number of recent 1-minute candles to use for price oscillation (chop) detection.
     * Higher values smooth out noise; lower values are more responsive.
     * Default: 10
     */
    private int oscillationCandleCount = 10;

    /**
     * Minimum number of direction reversals (close-to-close) within the oscillation window
     * to consider the market choppy/neutral. Each reversal means the candle closed in the
     * opposite direction of the previous candle.
     * Default: 4 (out of 9 possible in a 10-candle window)
     */
    private int oscillationMinReversals = 4;

    // ==================== V2: VWAP PULLBACK ENTRY SIGNAL ====================

    /**
     * Maximum deviation from VWAP (as fraction of VWAP) that qualifies as a "pullback zone".
     * Price must have deviated at least this much before reverting to trigger the signal.
     * Default: 0.003 (0.3%)
     */
    private double vwapPullbackThreshold = 0.003;

    /**
     * Maximum deviation from VWAP (as fraction of VWAP) that qualifies as "reverted to VWAP".
     * Price must be within this band of VWAP for the reversion leg to pass.
     * Default: 0.001 (0.1%)
     */
    private double vwapPullbackReversionThreshold = 0.001;

    /**
     * Number of recent candles to examine for the VWAP pullback pattern.
     * The detector looks for a deviation→reversion sequence within this window.
     * Default: 8
     */
    private int vwapPullbackCandleCount = 8;

    // ==================== V2: TIME-BASED ADAPTATION ====================

    /**
     * Enable time-based score adaptation.
     * When enabled, the score is adjusted based on market session:
     * - 09:15–10:00 (opening volatility): subtract 1 point (stricter)
     * - 10:00–13:30 (mid-session): no adjustment
     * - 13:30–15:00 (pre-close): add 1 point (more lenient)
     * Default: true
     */
    private boolean timeBasedAdaptationEnabled = true;

    // ==================== V2: WEIGHTED SCORING ====================

    /** Weight for VWAP Proximity signal. Default: 3 */
    private int weightVwap = 3;

    /** Weight for Range Compression signal. Default: 2 */
    private int weightRange = 2;

    /** Weight for Price Oscillation signal. Default: 2 */
    private int weightOscillation = 2;

    /** Weight for VWAP Pullback Entry signal. Default: 2 */
    private int weightVwapPullback = 2;

    /** Weight for ADX Trend Strength signal. Default: 1 */
    private int weightAdx = 1;

    /** Weight for Gamma Pin signal (expiry day only). Default: 1 */
    private int weightGammaPin = 1;

    // ==================== V2: REGIME THRESHOLDS ====================

    /**
     * Minimum score for STRONG_NEUTRAL regime (full position).
     * Default: 6
     */
    private int strongNeutralThreshold = 6;

    /**
     * Minimum score for WEAK_NEUTRAL regime (tradable with reduced size).
     * Default: 4
     */
    private int weakNeutralThreshold = 4;
}
