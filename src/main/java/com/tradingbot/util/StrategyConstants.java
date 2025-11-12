package com.tradingbot.util;

/**
 * Constants for trading strategies
 */
public class StrategyConstants {

    // Trading modes
    public static final String TRADING_MODE_PAPER = "PAPER";
    public static final String TRADING_MODE_LIVE = "LIVE";

    // Order statuses
    public static final String ORDER_STATUS_SUCCESS = "SUCCESS";
    public static final String ORDER_STATUS_COMPLETE = "COMPLETE";
    public static final String ORDER_STATUS_COMPLETED = "COMPLETED";

    // Order types
    public static final String ORDER_TYPE_MARKET = "MARKET";
    public static final String ORDER_TYPE_LIMIT = "LIMIT";

    // Transaction types
    public static final String TRANSACTION_BUY = "BUY";
    public static final String TRANSACTION_SELL = "SELL";

    // Option types
    public static final String OPTION_TYPE_CALL = "CE";
    public static final String OPTION_TYPE_PUT = "PE";

    // Strategy statuses
    public static final String STRATEGY_STATUS_ACTIVE = "ACTIVE";
    public static final String STRATEGY_STATUS_COMPLETED = "COMPLETED";
    public static final String STRATEGY_STATUS_FAILED = "FAILED";

    // Error messages
    public static final String ERROR_NO_RESPONSE = "No response received";
    public static final String ERROR_ATM_OPTIONS_NOT_FOUND = "ATM options not found for strike: ";
    public static final String ERROR_ORDER_PLACEMENT_FAILED = "order placement failed: ";
    public static final String ERROR_INVALID_ENTRY_PRICE = "Unable to fetch valid entry prices. Call: {}, Put: {}. Monitoring will not start.";
    public static final String ERROR_ORDER_HISTORY_FETCH = "Unable to fetch order history for callOrderId: {} or putOrderId: {}";

    // Log messages
    public static final String LOG_EXECUTING_STRATEGY = "[{} MODE] Executing ATM Straddle for {} with SL={}pts, Target={}pts";
    public static final String LOG_PLACING_ORDER = "[{} MODE] Placing {} order for {}";
    public static final String LOG_BOTH_LEGS_PLACED = "[{} MODE] Both legs placed successfully. Call Price: {}, Put Price: {}";
    public static final String LOG_STRATEGY_EXECUTED = "[{} MODE] ATM Straddle executed successfully. Total Premium: {}. Real-time monitoring started.";
    public static final String LOG_ORDER_NOT_COMPLETE = "{} order {} is not COMPLETE. Status: {}. Monitoring will not start.";
    public static final String LOG_EXITING_LEGS = "[{} MODE] Exiting all legs for execution {}: {}";
    public static final String LOG_LEG_EXITED = "[{} MODE] {} leg exited successfully: {}";
    public static final String LOG_ALL_LEGS_EXITED = "[{} MODE] Successfully exited all legs for execution {}";

    // Success messages
    public static final String MSG_STRATEGY_SUCCESS = "[%s MODE] ATM Straddle executed successfully. Monitoring with SL=%.1fpts, Target=%.1fpts";

    private StrategyConstants() {
        // Prevent instantiation
    }
}
