package com.tradingbot.util;

public final class ApiConstants {

    private ApiConstants() {
        throw new AssertionError("Cannot instantiate constants class");
    }

    // API Messages
    public static final String MSG_STRATEGY_EXECUTED_SUCCESS = "Strategy executed successfully";

    // Log Messages
    public static final String LOG_EXECUTE_STRATEGY_REQUEST = "API Request - Execute strategy: {} for instrument: {}";
    public static final String LOG_EXECUTE_STRATEGY_RESPONSE = "API Response - Strategy execution completed: {} with status: {}";
    public static final String LOG_GET_ACTIVE_STRATEGIES_REQUEST = "API Request - Get all active strategies";
    public static final String LOG_GET_ACTIVE_STRATEGIES_RESPONSE = "API Response - Found {} active strategies";
    public static final String LOG_GET_STRATEGY_REQUEST = "API Request - Get strategy details for executionId: {}";
    public static final String LOG_GET_STRATEGY_RESPONSE_NOT_FOUND = "API Response - Strategy not found: {}";
    public static final String LOG_GET_STRATEGY_RESPONSE_FOUND = "API Response - Strategy found: {} with status: {}";
    public static final String LOG_GET_STRATEGY_TYPES_REQUEST = "API Request - Get available strategy types";
    public static final String LOG_GET_STRATEGY_TYPES_RESPONSE = "API Response - Returning {} strategy types";
    public static final String LOG_GET_INSTRUMENTS_REQUEST = "API Request - Get available instruments";
}

