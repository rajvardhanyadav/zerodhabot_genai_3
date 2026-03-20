package com.tradingbot.model;

import java.time.Instant;
import java.util.List;

/**
 * Common interface for neutral market evaluation results.
 *
 * <p>Abstracts over the differences between {@link NeutralMarketResult} (V2) and
 * {@link NeutralMarketResultV3} (V3) so that consumers (strategies, updaters, schedulers)
 * can be wired to any detector version without coupling to concrete result classes.</p>
 *
 * <h2>HFT Safety</h2>
 * All implementations must be immutable after construction. Getters must return
 * primitives, pre-computed strings, or unmodifiable collections. No allocation on
 * the read path.
 *
 * @since 6.1
 * @see NeutralMarketResult
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

    /** Composite score (meaning varies by version: 0–10 for V2, 0–15 for V3). */
    int totalScore();

    /** Maximum possible composite score. */
    int maxScore();

    /** Human-readable summary for logging. */
    String summary();

    /** Timestamp of the evaluation (IST). */
    Instant evaluatedAt();

    /**
     * Market regime classification as a human-readable label.
     * V2 returns "STRONG_NEUTRAL" / "WEAK_NEUTRAL" / "TRENDING" (String).
     * V3 returns the {@link Regime} enum's {@code name()}.
     */
    String getRegimeLabel();

    /**
     * Minimum score required for tradability (V2 concept).
     * V3 implementations may return 0 (the concept does not apply in the 3-layer model).
     */
    int minimumRequired();

    /**
     * Per-signal evaluation results.
     * V2 returns a populated list of {@link SignalResult} objects.
     * V3 returns an empty list (V3 uses a different internal signal model).
     */
    List<SignalResult> signals();
}

