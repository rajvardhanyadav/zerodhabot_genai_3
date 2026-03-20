package com.tradingbot.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of neutral market evaluation with confidence scoring and regime classification.
 *
 * <p>Replaces the previous binary pass/fail model with a weighted confidence score (0–10),
 * regime classification (STRONG_NEUTRAL / WEAK_NEUTRAL / TRENDING), and per-signal breakdown.
 * Optimised for the SELL ATM STRADDLE intraday strategy on NIFTY 50.</p>
 *
 * <h2>Regime Classification</h2>
 * <ul>
 *   <li><b>STRONG_NEUTRAL</b> (score ≥ 6): High confidence neutral — full position size</li>
 *   <li><b>WEAK_NEUTRAL</b> (score ≥ 4): Moderate confidence — reduced position size</li>
 *   <li><b>TRENDING</b> (score &lt; 4): Not tradable — skip entry</li>
 * </ul>
 *
 * <h2>Backward Compatibility</h2>
 * Provides {@link #neutral()}, {@link #totalScore()}, {@link #maxScore()}, {@link #signals()},
 * {@link #summary()}, {@link #evaluatedAt()}, {@link #minimumRequired()} for seamless integration
 * with existing consumers ({@code MarketStateUpdater}, {@code StrategyRestartScheduler}).
 *
 * <h2>HFT Safety</h2>
 * Immutable after construction. No mutable fields. Pre-allocated singletons for disabled/unavailable states.
 *
 * @since 5.0
 */
public final class NeutralMarketResult implements NeutralMarketEvaluation {

    // ==================== REGIME CONSTANTS ====================
    public static final String REGIME_STRONG_NEUTRAL = "STRONG_NEUTRAL";
    public static final String REGIME_WEAK_NEUTRAL = "WEAK_NEUTRAL";
    public static final String REGIME_TRENDING = "TRENDING";

    public static final int MAX_SCORE = 10;

    // ==================== FIELDS ====================
    private final boolean tradable;
    private final int score;
    private final double confidence;
    private final String regime;
    private final Map<String, Boolean> signalBreakdown;
    private final List<SignalResult> signals;
    private final String summary;
    private final Instant evaluatedAt;
    private final int minimumRequired;

    // ==================== CONSTRUCTOR ====================
    public NeutralMarketResult(boolean tradable, int score, double confidence, String regime,
                               Map<String, Boolean> signalBreakdown, List<SignalResult> signals,
                               String summary, Instant evaluatedAt, int minimumRequired) {
        this.tradable = tradable;
        this.score = Math.min(score, MAX_SCORE);
        this.confidence = confidence;
        this.regime = regime;
        this.signalBreakdown = signalBreakdown != null ? Collections.unmodifiableMap(signalBreakdown) : Collections.emptyMap();
        this.signals = signals != null ? Collections.unmodifiableList(signals) : Collections.emptyList();
        this.summary = summary;
        this.evaluatedAt = evaluatedAt;
        this.minimumRequired = minimumRequired;
    }

    // ==================== V2 ACCESSORS ====================

    /** Whether the market is tradable (score ≥ 4 → WEAK_NEUTRAL or STRONG_NEUTRAL). */
    public boolean isTradable() { return tradable; }

    /** Weighted score (0–10). */
    public int getScore() { return score; }

    /** Confidence as fraction (0.0–1.0), derived from score/maxScore. */
    public double getConfidence() { return confidence; }

    /** Market regime: STRONG_NEUTRAL, WEAK_NEUTRAL, or TRENDING. */
    public String getRegime() { return regime; }

    /** Per-signal pass/fail breakdown. Key = signal name, Value = passed. */
    public Map<String, Boolean> getSignalBreakdown() { return signalBreakdown; }

    // ==================== NeutralMarketEvaluation contract ====================

    /** {@inheritDoc} */
    @Override
    public String getRegimeLabel() { return regime; }

    // ==================== BACKWARD-COMPAT ACCESSORS ====================

    /** Alias for {@link #isTradable()} — backward compat with V1 consumers. */
    public boolean neutral() { return tradable; }

    /** Alias for {@link #getScore()} — backward compat with V1 consumers. */
    public int totalScore() { return score; }

    /** Maximum possible score (always 10). */
    public int maxScore() { return MAX_SCORE; }

    /** Per-signal results list — backward compat with V1 consumers. */
    public List<SignalResult> signals() { return signals; }

    /** Human-readable summary of signal evaluations. */
    public String summary() { return summary; }

    /** Timestamp of evaluation. */
    public Instant evaluatedAt() { return evaluatedAt; }

    /** Minimum score required for tradable — backward compat. */
    public int minimumRequired() { return minimumRequired; }

    // ==================== FACTORY METHODS ====================

    /** Factory for when data is entirely unavailable. */
    public static NeutralMarketResult dataUnavailable(boolean allowTrade, String reason, int minimumRequired) {
        return new NeutralMarketResult(
                allowTrade, 0, 0.0, REGIME_TRENDING,
                Collections.emptyMap(), Collections.emptyList(),
                "Data unavailable: " + reason + ". Fail-safe: " + (allowTrade ? "ALLOW" : "BLOCK"),
                Instant.now(), minimumRequired
        );
    }

    /** Factory for disabled filter — always allow trade with max score. */
    public static NeutralMarketResult disabled() {
        return new NeutralMarketResult(
                true, MAX_SCORE, 1.0, REGIME_STRONG_NEUTRAL,
                Collections.emptyMap(), Collections.emptyList(),
                "Neutral market filter disabled",
                Instant.now(), 0
        );
    }

    @Override
    public String toString() {
        return "NeutralMarketResult{" +
                "tradable=" + tradable +
                ", score=" + score +
                ", confidence=" + String.format("%.2f", confidence) +
                ", regime='" + regime + '\'' +
                ", summary='" + summary + '\'' +
                '}';
    }
}

