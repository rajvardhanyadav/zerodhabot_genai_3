package com.tradingbot.model;

/**
 * Structured reasons why a strategy execution finished.
 * Used to drive behaviours like auto-restart scheduling.
 */
public enum StrategyCompletionReason {

    /**
     * Strategy closed because overall target profit was hit.
     */
    TARGET_HIT,

    /**
     * Strategy closed because stop loss was hit.
     */
    STOPLOSS_HIT,

    /**
     * Strategy was stopped manually by the user or operator.
     */
    MANUAL_STOP,

    /**
     * Strategy closed due to time-based forced exit (auto square-off at market close).
     * This is a normal completion, not an error - positions are squared off to avoid overnight risk.
     */
    TIME_BASED_EXIT,

    /**
     * Strategy ended due to an error or unexpected condition.
     */
    ERROR,

    /**
     * Generic fallback when no specific reason is available.
     */
    OTHER
}

