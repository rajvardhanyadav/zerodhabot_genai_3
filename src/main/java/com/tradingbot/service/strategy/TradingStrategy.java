package com.tradingbot.service.strategy;

import com.tradingbot.dto.StrategyExecutionResponse;
import com.tradingbot.dto.StrategyRequest;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;

import java.io.IOException;

/**
 * Base interface for all trading strategies
 */
public interface TradingStrategy {

    /**
     * Execute the strategy
     *
     * @param request Strategy execution request with parameters
     * @param executionId Unique execution ID for tracking
     * @param completionCallback Callback to notify when strategy is completed
     * @return Strategy execution response with order details
     * @throws KiteException if Kite API call fails
     * @throws IOException if network error occurs
     */
    StrategyExecutionResponse execute(StrategyRequest request, String executionId,
                                     StrategyCompletionCallback completionCallback)
            throws KiteException, IOException;

    /**
     * Get strategy name
     *
     * @return Strategy name
     */
    String getStrategyName();

    /**
     * Get strategy description
     *
     * @return Strategy description
     */
    String getStrategyDescription();
}
