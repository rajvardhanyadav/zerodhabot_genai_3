package com.tradingbot.model;

/**
 * Enum representing different trading strategy types supported by the trading bot.
 * Each strategy type defines a specific options trading approach.
 */
public enum StrategyType {
    /**
     * At-The-Money Straddle: Buying both Call and Put options at the same strike price (ATM)
     * Non-directional strategy that profits from high volatility
     */
    ATM_STRADDLE,

    /**
     * Sell At-The-Money Straddle: Selling both Call and Put options at the same strike price (ATM)
     * Non-directional strategy that profits from low volatility and time decay
     */
    SELL_ATM_STRADDLE
}
