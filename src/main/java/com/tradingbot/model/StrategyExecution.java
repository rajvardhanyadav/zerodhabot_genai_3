package com.tradingbot.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Model representing the execution state of a trading strategy.
 * Tracks strategy details, execution status, and associated order legs.
 */
@Data
@NoArgsConstructor
public class StrategyExecution {

    // Identification
    private String executionId;
    private StrategyType strategyType;

    // User context
    private String userId;

    // Strategy parameters
    private String instrumentType; // NIFTY, BANKNIFTY, FINNIFTY
    private String expiry; // Format: yyyy-MM-dd or WEEKLY/MONTHLY

    // Execution state
    private StrategyStatus status; // Lifecycle state of the strategy
    private String message;
    private Long timestamp;

    // Financial metrics
    private Double entryPrice;
    private Double currentPrice;
    private Double profitLoss;

    // Order tracking - initialized to empty list to avoid null checks
    private List<OrderLeg> orderLegs = new ArrayList<>();

    /**
     * Represents a single leg (order) within a multi-leg strategy.
     * Each leg corresponds to one option contract (CE or PE) in the strategy.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderLeg {
        /** Unique order ID from the broker */
        private String orderId;

        /** Trading symbol (e.g., NIFTY24NOVFUT) */
        private String tradingSymbol;

        /** Option type: CE (Call) or PE (Put) */
        private String optionType;

        /** Number of contracts/shares */
        private Integer quantity;

        /** Entry price of this leg */
        private Double entryPrice;
    }
}
