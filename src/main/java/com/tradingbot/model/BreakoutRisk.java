package com.tradingbot.model;

/**
 * Breakout risk classification for NeutralMarketDetectorServiceV3.
 *
 * <p>Evaluated by the breakout risk layer which detects the classic breakout setup:</p>
 * <ol>
 *   <li>Very tight consolidation range (spring loading)</li>
 *   <li>Price pressing against range edge (ready to break)</li>
 *   <li>Building directional momentum (about to thrust)</li>
 * </ol>
 *
 * <p>When all three conditions align, the risk of entering an ATM straddle is HIGH
 * because a breakout would cause one leg to move sharply against the position.</p>
 *
 * @since 6.0
 */
public enum BreakoutRisk {

    /** 0–1 breakout signals present. Safe to enter straddle. */
    LOW,

    /** 2 breakout signals present. Enter with caution / reduced size. */
    MEDIUM,

    /** All 3 breakout signals present. Do NOT enter straddle — breakout imminent. */
    HIGH
}

