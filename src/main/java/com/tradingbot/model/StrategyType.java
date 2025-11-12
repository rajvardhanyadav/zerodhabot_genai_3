package com.tradingbot.model;

/**
 * Enum representing different trading strategy types supported by the trading bot.
 * Each strategy type defines a specific options trading approach.
 */
public enum StrategyType {
    /**
     * At-The-Money Straddle: Buying/Selling both Call and Put options at the same strike price (ATM)
     */
    ATM_STRADDLE,

    /**
     * At-The-Money Strangle: Buying/Selling Call and Put options at different strike prices around ATM
     */
    ATM_STRANGLE,

    /**
     * Bull Call Spread: Bullish strategy using two call options at different strikes
     */
    BULL_CALL_SPREAD,

    /**
     * Bear Put Spread: Bearish strategy using two put options at different strikes
     */
    BEAR_PUT_SPREAD,

    /**
     * Iron Condor: Neutral strategy combining bull put spread and bear call spread
     */
    IRON_CONDOR,

    /**
     * Custom strategy defined by the user
     */
    CUSTOM
}
