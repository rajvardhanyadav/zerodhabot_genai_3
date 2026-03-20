package com.tradingbot.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Result of the 3-layer neutral market detection engine (V3).
 *
 * <p>Combines regime analysis, microstructure opportunity detection, and breakout risk
 * assessment into a single actionable decision object. Designed for the SELL ATM STRADDLE
 * intraday strategy on NIFTY 50.</p>
 *
 * <h2>Score Breakdown</h2>
 * <ul>
 *   <li><b>Regime Score</b> (0–9): Macro neutrality from VWAP, range, oscillation, ADX, gamma pin</li>
 *   <li><b>Micro Score</b> (0–5): Immediate tradable opportunity from pullback, HF oscillation, range stability</li>
 *   <li><b>Final Score</b> (0–14+): regime + micro, time-adjusted. Used for confidence and lot sizing.</li>
 * </ul>
 *
 * <h2>HFT Safety</h2>
 * Immutable after construction. No mutable fields. All getters return primitives or
 * unmodifiable collections. Safe for concurrent read access from multiple strategy threads.
 *
 * @since 6.0
 * @see Regime
 * @see BreakoutRisk
 */
public final class NeutralMarketResultV3 {

    // ==================== PRE-ALLOCATED SINGLETON FOR DISABLED STATE ====================
    private static final NeutralMarketResultV3 DISABLED = new NeutralMarketResultV3(
            true, 10, 5, 15, 1.0,
            Regime.STRONG_NEUTRAL, BreakoutRisk.LOW,
            true, 3, Collections.emptyMap(),
            "V3 filter disabled — allowing trade", Instant.EPOCH
    );

    // ==================== FIELDS (all final, immutable) ====================
    private final boolean tradable;
    private final int regimeScore;
    private final int microScore;
    private final int finalScore;
    private final double confidence;
    private final Regime regime;
    private final BreakoutRisk breakoutRisk;
    private final boolean microTradable;
    private final int recommendedLotMultiplier;
    private final Map<String, Boolean> signals;
    private final String summary;
    private final Instant evaluatedAt;

    // ==================== CONSTRUCTOR ====================
    public NeutralMarketResultV3(boolean tradable, int regimeScore, int microScore, int finalScore,
                                 double confidence, Regime regime, BreakoutRisk breakoutRisk,
                                 boolean microTradable, int recommendedLotMultiplier,
                                 Map<String, Boolean> signals, String summary, Instant evaluatedAt) {
        this.tradable = tradable;
        this.regimeScore = regimeScore;
        this.microScore = microScore;
        this.finalScore = finalScore;
        this.confidence = confidence;
        this.regime = regime;
        this.breakoutRisk = breakoutRisk;
        this.microTradable = microTradable;
        this.recommendedLotMultiplier = recommendedLotMultiplier;
        this.signals = signals != null ? Collections.unmodifiableMap(new LinkedHashMap<>(signals)) : Collections.emptyMap();
        this.summary = summary;
        this.evaluatedAt = evaluatedAt;
    }

    // ==================== ACCESSORS ====================

    /** Whether a straddle trade should be placed now. Final decision incorporating all 3 layers. */
    public boolean isTradable() { return tradable; }

    /** Regime layer score (0–9). Measures overall market neutrality. */
    public int getRegimeScore() { return regimeScore; }

    /** Microstructure layer score (0–5). Measures immediate tradable opportunity. */
    public int getMicroScore() { return microScore; }

    /** Combined score = regimeScore + microScore, time-adjusted. Used for confidence and lot sizing. */
    public int getFinalScore() { return finalScore; }

    /** Confidence as a fraction (0.0–1.0), derived from finalScore / 15.0. */
    public double getConfidence() { return confidence; }

    /** Market regime classification (STRONG_NEUTRAL, WEAK_NEUTRAL, TRENDING). */
    public Regime getRegime() { return regime; }

    /** Breakout risk assessment. HIGH = do not trade. */
    public BreakoutRisk getBreakoutRisk() { return breakoutRisk; }

    /** Whether the microstructure layer independently signals a tradable opportunity. */
    public boolean isMicroTradable() { return microTradable; }

    /** Recommended lot multiplier (0–3) based on finalScore. 0 = do not trade. */
    public int getRecommendedLotMultiplier() { return recommendedLotMultiplier; }

    /** Per-signal pass/fail breakdown. Key = signal name, Value = passed. */
    public Map<String, Boolean> getSignals() { return signals; }

    /** Human-readable summary for logging. */
    public String getSummary() { return summary; }

    /** Timestamp of evaluation (IST). */
    public Instant getEvaluatedAt() { return evaluatedAt; }

    // ==================== BACKWARD-COMPAT ACCESSORS ====================
    // Compatible with MarketStateEvent consumers that use NeutralMarketResult conventions

    /** Alias for {@link #isTradable()} — backward compat. */
    public boolean neutral() { return tradable; }

    /** Alias for {@link #getFinalScore()} — backward compat. */
    public int totalScore() { return finalScore; }

    /** Maximum possible final score (~15). */
    public int maxScore() { return 15; }

    /** Alias for {@link #getSummary()} — backward compat. */
    public String summary() { return summary; }

    /** Alias for {@link #getEvaluatedAt()} — backward compat. */
    public Instant evaluatedAt() { return evaluatedAt; }

    // ==================== FACTORY METHODS ====================

    /** Pre-allocated result for disabled filter — zero allocation. */
    public static NeutralMarketResultV3 disabled() {
        return DISABLED;
    }

    /** Factory for when market data is entirely unavailable. */
    public static NeutralMarketResultV3 dataUnavailable(boolean allowTrade, String reason) {
        return new NeutralMarketResultV3(
                allowTrade, 0, 0, 0, 0.0,
                Regime.TRENDING, BreakoutRisk.LOW,
                false, 0, Collections.emptyMap(),
                "Data unavailable: " + reason + ". Fail-safe: " + (allowTrade ? "ALLOW" : "BLOCK"),
                Instant.now()
        );
    }

    @Override
    public String toString() {
        return "NeutralMarketResultV3{" +
                "tradable=" + tradable +
                ", regime=" + regime +
                ", breakoutRisk=" + breakoutRisk +
                ", regimeScore=" + regimeScore +
                ", microScore=" + microScore +
                ", finalScore=" + finalScore +
                ", confidence=" + String.format("%.2f", confidence) +
                ", lotMultiplier=" + recommendedLotMultiplier +
                '}';
    }
}

