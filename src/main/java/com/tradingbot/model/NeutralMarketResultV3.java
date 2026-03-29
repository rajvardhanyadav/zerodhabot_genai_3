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
 *   <li><b>Final Score</b> (0–14+): regime + micro, time-adjusted. Used for confidence.</li>
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
public final class NeutralMarketResultV3 implements NeutralMarketEvaluation {

    // ==================== PRE-ALLOCATED SINGLETON FOR DISABLED STATE ====================
    private static final NeutralMarketResultV3 DISABLED = new NeutralMarketResultV3(
            true, 10, 5, 15, 1.0,
            Regime.STRONG_NEUTRAL, BreakoutRisk.LOW,
            true, Collections.emptyMap(),
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
    private final Map<String, Boolean> signals;
    private final String summary;
    private final Instant evaluatedAt;

    // ==================== ENRICHMENT FIELDS FOR PERSISTENCE ====================
    // These fields capture raw evaluation context so the persistence layer can
    // store complete diagnostics without re-computation. Set via the enriched constructor
    // or via the enrich() builder. All default to safe zero/null values for backward compat.
    private final double spotPrice;
    private final double vwapValue;
    private final long evaluationDurationMs;
    private final String vetoReason;
    private final int timeAdjustment;
    private final boolean expiryDay;
    // Per-signal numeric values (beyond pass/fail)
    private final double vwapDeviation;
    private final double rangeFraction;
    private final int oscillationReversals;
    private final double adxValue;

    // ==================== CONSTRUCTOR (original — backward compat) ====================
    public NeutralMarketResultV3(boolean tradable, int regimeScore, int microScore, int finalScore,
                                 double confidence, Regime regime, BreakoutRisk breakoutRisk,
                                 boolean microTradable,
                                 Map<String, Boolean> signals, String summary, Instant evaluatedAt) {
        this(tradable, regimeScore, microScore, finalScore, confidence, regime, breakoutRisk,
                microTradable, signals, summary, evaluatedAt,
                0.0, 0.0, 0L, null, 0, false,
                0.0, 0.0, 0, 0.0);
    }

    // ==================== CONSTRUCTOR (enriched — with persistence detail) ====================
    public NeutralMarketResultV3(boolean tradable, int regimeScore, int microScore, int finalScore,
                                 double confidence, Regime regime, BreakoutRisk breakoutRisk,
                                 boolean microTradable,
                                 Map<String, Boolean> signals, String summary, Instant evaluatedAt,
                                 double spotPrice, double vwapValue, long evaluationDurationMs,
                                 String vetoReason, int timeAdjustment, boolean expiryDay,
                                 double vwapDeviation, double rangeFraction,
                                 int oscillationReversals, double adxValue) {
        this.tradable = tradable;
        this.regimeScore = regimeScore;
        this.microScore = microScore;
        this.finalScore = finalScore;
        this.confidence = confidence;
        this.regime = regime;
        this.breakoutRisk = breakoutRisk;
        this.microTradable = microTradable;
        this.signals = signals != null ? Collections.unmodifiableMap(new LinkedHashMap<>(signals)) : Collections.emptyMap();
        this.summary = summary;
        this.evaluatedAt = evaluatedAt;
        this.spotPrice = spotPrice;
        this.vwapValue = vwapValue;
        this.evaluationDurationMs = evaluationDurationMs;
        this.vetoReason = vetoReason;
        this.timeAdjustment = timeAdjustment;
        this.expiryDay = expiryDay;
        this.vwapDeviation = vwapDeviation;
        this.rangeFraction = rangeFraction;
        this.oscillationReversals = oscillationReversals;
        this.adxValue = adxValue;
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

    /** Per-signal pass/fail breakdown. Key = signal name, Value = passed. */
    public Map<String, Boolean> getSignals() { return signals; }

    /** Human-readable summary for logging. */
    public String getSummary() { return summary; }

    /** Timestamp of evaluation (IST). */
    public Instant getEvaluatedAt() { return evaluatedAt; }

    // ==================== ENRICHMENT ACCESSORS (for persistence) ====================

    /** NIFTY spot price at time of evaluation. 0 if not enriched. */
    public double getSpotPrice() { return spotPrice; }

    /** Computed VWAP value. 0 if not enriched or unavailable. */
    public double getVwapValue() { return vwapValue; }

    /** Evaluation duration in milliseconds. 0 if not enriched. */
    public long getEvaluationDurationMs() { return evaluationDurationMs; }

    /** Veto reason string (e.g., "BREAKOUT_HIGH", "EXCESSIVE_RANGE"). Null if no veto. */
    public String getVetoReason() { return vetoReason; }

    /** Time-based adjustment applied to final score. 0 if none. */
    public int getTimeAdjustment() { return timeAdjustment; }

    /** Whether the evaluation occurred on an expiry day. */
    public boolean isExpiryDay() { return expiryDay; }

    /** R1 VWAP deviation fraction (|price − VWAP| / VWAP). */
    public double getVwapDeviation() { return vwapDeviation; }

    /** R2 Range fraction ((highest − lowest) / price). */
    public double getRangeFraction() { return rangeFraction; }

    /** R3 Number of direction reversals in oscillation check. */
    public int getOscillationReversals() { return oscillationReversals; }

    /** R4 Latest ADX value. 0 if unavailable. */
    public double getAdxValue() { return adxValue; }

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

    // ==================== NeutralMarketEvaluation contract ====================

    /** {@inheritDoc} V3 regime label derived from the {@link Regime} enum. */
    @Override
    public String getRegimeLabel() { return regime != null ? regime.name() : "UNKNOWN"; }

    /**
     * {@inheritDoc}
     * V3 does not use a minimum-score threshold (the 3-layer model replaces it).
     * Returns 0 for backward compatibility.
     */
    @Override
    public int minimumRequired() { return 0; }


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
                false, Collections.emptyMap(),
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
                '}';
    }
}
