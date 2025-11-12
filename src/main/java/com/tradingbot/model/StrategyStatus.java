package com.tradingbot.model;

/**
 * Enum representing the lifecycle states of a strategy execution.
 * Tracks the progression from initiation to completion or failure.
 */
public enum StrategyStatus {
    /**
     * Strategy has been queued but not yet started
     */
    PENDING,
    
    /**
     * Strategy is currently being executed (orders are being placed)
     */
    EXECUTING,
    
    /**
     * Strategy is active with open positions being monitored
     */
    ACTIVE,
    
    /**
     * Strategy has been completed successfully (all positions closed)
     */
    COMPLETED,
    
    /**
     * Strategy execution failed due to an error
     */
    FAILED
}

