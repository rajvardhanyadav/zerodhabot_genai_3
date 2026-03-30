package com.tradingbot.model;

import java.time.Instant;

/**
 * Common interface for neutral market evaluation results.
 *
 * <p>Currently implemented by {@link NeutralMarketResultV3} which provides
 * 3-layer regime scoring, microstructure bonuses, and breakout risk veto gates.</p>
 *
 * <h2>HFT Safety</h2>
 * All implementations must be immutable after construction. Getters must return
 * primitives, pre-computed strings, or unmodifiable collections. No allocation on
 * the read path.
 *
 * @since 6.1
 * @see NeutralMarketResultV3
 */
public interface NeutralMarketEvaluation {

    /**
     * Whether the market is considered neutral/tradable.
     * Equivalent to {@code isTradable()} — retained for backward compatibility.
     */
    boolean neutral();

    /** Whether the market is tradable (alias for {@link #neutral()}). */
    boolean isTradable();

    /** Composite score (meaning varies by implementation). */
    int totalScore();

    /** Maximum possible composite score. */
    int maxScore();

    /** Human-readable summary for logging. */
    String summary();

    /** Timestamp of the evaluation (IST). */
    Instant evaluatedAt();

    /**
     * Market regime classification as a human-readable label.
     * Returns the {@link Regime} enum's {@code name()}.
     */
    String getRegimeLabel();

    /**
     * Minimum score required for tradability.
     * V3 implementations return 0 (the concept does not apply in the 3-layer model).
     */
    int minimumRequired();

    /**
     * Veto reason that blocked tradability despite a neutral regime.
     * Returns {@code null} if no veto was applied or the market was tradable.
     * Typical values: "BREAKOUT_HIGH", "EXCESSIVE_RANGE".
     */
    default String getVetoReason() { return null; }
}

