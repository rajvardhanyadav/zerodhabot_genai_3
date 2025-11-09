package com.tradingbot.service.strategy;

/**
 * Callback interface for strategy completion notifications
 * This allows strategies to notify when they are completed without creating circular dependencies
 */
@FunctionalInterface
public interface StrategyCompletionCallback {

    /**
     * Called when a strategy execution is completed
     *
     * @param executionId The execution ID of the completed strategy
     * @param reason The reason for completion (e.g., "Stop Loss Hit", "Target Reached")
     */
    void onStrategyCompleted(String executionId, String reason);
}

