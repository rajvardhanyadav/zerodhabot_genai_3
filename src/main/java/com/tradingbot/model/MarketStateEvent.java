package com.tradingbot.model;


import java.time.Instant;

/**
 * Event published by {@code MarketStateUpdater} every evaluation cycle.
 * Consumers (e.g. {@code StrategyRestartScheduler}) listen for this event
 * instead of polling {@code NeutralMarketDetectorService} directly.
 *
 * <p>This decouples market-state evaluation from strategy restart logic,
 * eliminating thread-blocking polling loops and redundant API calls.
 *
 * @param instrumentType instrument evaluated (e.g. "NIFTY", "BANKNIFTY")
 * @param neutral        whether the market was determined to be neutral/range-bound
 * @param score          composite neutrality score (version-dependent: 0–10 for V2, 0–15 for V3)
 * @param maxScore       maximum possible score
 * @param result         full evaluation result with per-signal breakdown (V2 or V3)
 * @param evaluatedAt    timestamp of the evaluation
 * @since 4.2
 */
public record MarketStateEvent(
        String instrumentType,
        boolean neutral,
        int score,
        int maxScore,
        NeutralMarketEvaluation result,
        Instant evaluatedAt
) {
}

