package com.tradingbot.model;

/**
 * Enum representing different stop-loss and target calculation modes for strategy execution.
 * Determines how SL/Target thresholds are computed and monitored.
 */
public enum SlTargetMode {

    /**
     * Points-based mode: SL/Target are fixed point values.
     * Exit when cumulative P&L hits +targetPoints (profit) or -stopLossPoints (loss).
     */
    POINTS,

    /**
     * Premium-based mode: SL/Target are percentage-based on combined entry premium.
     * Exit when combined LTP decays by targetDecayPct (profit) or expands by stopLossExpansionPct (loss).
     */
    PREMIUM,

    /**
     * MTM-based mode: SL/Target based on mark-to-market P&L.
     * Traditional MTM calculation for exit triggers.
     */
    MTM
}

