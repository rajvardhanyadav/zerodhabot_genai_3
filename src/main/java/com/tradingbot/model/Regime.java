package com.tradingbot.model;

/**
 * Market regime classification for NeutralMarketDetectorServiceV3.
 *
 * <p>Derived from the regime layer composite score:</p>
 * <ul>
 *   <li>{@link #STRONG_NEUTRAL} (score ≥ 6) — high confidence, full position size</li>
 *   <li>{@link #WEAK_NEUTRAL} (score ≥ 4) — moderate confidence, reduced position size</li>
 *   <li>{@link #TRENDING} (score &lt; 4) — not tradable for straddle strategies</li>
 * </ul>
 *
 * @since 6.0
 */
public enum Regime {

    /** Score ≥ 6: Market is range-bound with high confidence. Full position allowed. */
    STRONG_NEUTRAL,

    /** Score ≥ 4: Market shows neutral tendencies but with lower confidence. Reduced size recommended. */
    WEAK_NEUTRAL,

    /** Score &lt; 4: Market is trending or volatile. Straddle entry should be skipped. */
    TRENDING
}

