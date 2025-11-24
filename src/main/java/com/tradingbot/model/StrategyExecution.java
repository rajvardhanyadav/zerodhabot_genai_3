package com.tradingbot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Model representing the execution state of a trading strategy.
 * Tracks strategy details, execution status, and associated order legs.
 */
@Data // Keep for now to avoid breaking callers that rely on Lombok-generated methods
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyExecution {

    // Identification
    private String executionId;
    private StrategyType strategyType;

    // Root of the auto-restart chain so clients can group all re-entries
    private String rootExecutionId;

    // Link to the previous execution in an auto-restart chain (may be null for first run)
    private String parentExecutionId;

    // User context
    private String userId;

    // Strategy parameters
    private String instrumentType; // NIFTY, BANKNIFTY, FINNIFTY
    private String expiry; // Format: yyyy-MM-dd or WEEKLY/MONTHLY

    // Execution state
    private StrategyStatus status; // Lifecycle state of the strategy
    private String message;
    private Long timestamp;

    // New: structured completion reason (e.g. TARGET_HIT, STOPLOSS_HIT, MANUAL_STOP)
    private StrategyCompletionReason completionReason;

    // Optional: how many times this execution chain has auto-restarted
    private int autoRestartCount;

    // Track which trading mode (PAPER or LIVE) the execution used
    private String tradingMode;

    public boolean isPaperTradingMode() {
        return com.tradingbot.util.StrategyConstants.TRADING_MODE_PAPER.equalsIgnoreCase(tradingMode);
    }

    public boolean isLiveTradingMode() {
        return com.tradingbot.util.StrategyConstants.TRADING_MODE_LIVE.equalsIgnoreCase(tradingMode);
    }

    // Financial metrics
    private Double entryPrice;
    private Double currentPrice;
    private Double profitLoss;

    // Order tracking - initialized to empty list to avoid null checks
    @Builder.Default
    private List<OrderLeg> orderLegs = new ArrayList<>();

    /**
     * Represents a single leg (order) within a multi-leg strategy.
     * Each leg corresponds to one option contract (CE or PE) in the strategy.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
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

        /** BUY/SELL used to enter the leg */
        private String entryTransactionType;

        /** Epoch millis when the entry order was acknowledged */
        private Long entryTimestamp;

        /** Current lifecycle state of the leg */
        @Builder.Default
        private LegLifecycleState lifecycleState = LegLifecycleState.OPEN;

        /** Metadata captured when the leg is closed */
        private String exitOrderId;
        private String exitTransactionType;
        private Integer exitQuantity;
        private Double exitPrice;
        private Long exitRequestedAt;
        private Long exitTimestamp;
        private String exitStatus;
        private String exitMessage;
        private Double realizedPnl;
    }

    /**
     * Lifecycle states tracked for each order leg.
     */
    public enum LegLifecycleState {
        OPEN,
        EXIT_PENDING,
        EXITED,
        EXIT_FAILED
    }
}
