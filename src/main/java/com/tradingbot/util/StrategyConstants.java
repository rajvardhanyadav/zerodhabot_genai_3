package com.tradingbot.util;

/**
 * Constants for trading strategies
 * Centralizes all constant values used across the trading bot application
 */
public final class StrategyConstants {

    // ==================== Trading Modes ====================
    public static final String TRADING_MODE_PAPER = "PAPER";
    public static final String TRADING_MODE_LIVE = "LIVE";

    // ==================== Order Statuses ====================
    /**
     * Order status returned by order placement API
     */
    public static final String ORDER_STATUS_SUCCESS = "SUCCESS";

    /**
     * Order status when order is fully executed (from Kite API)
     */
    public static final String ORDER_STATUS_COMPLETE = "COMPLETE";

    // ==================== Order Types ====================
    public static final String ORDER_TYPE_MARKET = "MARKET";

    // ==================== Transaction Types ====================
    public static final String TRANSACTION_BUY = "BUY";
    public static final String TRANSACTION_SELL = "SELL";

    // ==================== Option Types ====================
    /**
     * Call option type (CE = Call European)
     */
    public static final String OPTION_TYPE_CALL = "CE";

    /**
     * Put option type (PE = Put European)
     */
    public static final String OPTION_TYPE_PUT = "PE";

    // ==================== Strategy Statuses ====================
    public static final String STRATEGY_STATUS_ACTIVE = "ACTIVE";

    // ==================== Error Messages ====================
    public static final String ERROR_NO_RESPONSE = "No response received";
    public static final String ERROR_ATM_OPTIONS_NOT_FOUND = "ATM options not found for strike: ";
    public static final String ERROR_ORDER_PLACEMENT_FAILED = "order placement failed: ";
    public static final String ERROR_INVALID_ENTRY_PRICE = "Unable to fetch valid entry prices. Call: {}, Put: {}. Monitoring will not start.";
    public static final String ERROR_ORDER_HISTORY_FETCH = "Unable to fetch order history for callOrderId: {} or putOrderId: {}";

    // ==================== Log Messages ====================
    public static final String LOG_EXECUTING_STRATEGY = "[{} MODE] Executing ATM Straddle for {} with SL={}pts, Target={}pts";
    public static final String LOG_PLACING_ORDER = "[{} MODE] Placing {} order for {}";
    public static final String LOG_BOTH_LEGS_PLACED = "[{} MODE] Both legs placed successfully. Call Price: {}, Put Price: {}";
    public static final String LOG_STRATEGY_EXECUTED = "[{} MODE] ATM Straddle executed successfully. Total Premium: {}. Real-time monitoring started.";
    public static final String LOG_ORDER_NOT_COMPLETE = "{} order {} is not COMPLETE. Status: {}. Monitoring will not start.";
    public static final String LOG_EXITING_LEGS = "[{} MODE] Exiting all legs for execution {}: {}";
    public static final String LOG_LEG_EXITED = "[{} MODE] {} leg exited successfully: {}";
    public static final String LOG_ALL_LEGS_EXITED = "[{} MODE] Successfully exited all legs for execution {}";

    // ==================== Success Messages ====================
    public static final String MSG_STRATEGY_SUCCESS = "[%s MODE] ATM Straddle executed successfully. Monitoring with SL=%.1fpts, Target=%.1fpts";

    /**
     * Private constructor to prevent instantiation
     */
    private StrategyConstants() {
        throw new AssertionError("Cannot instantiate constants class");
    }
}
