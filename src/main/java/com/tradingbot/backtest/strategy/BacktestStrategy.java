package com.tradingbot.backtest.strategy;

import com.tradingbot.backtest.dto.BacktestRequest;
import com.tradingbot.backtest.dto.BacktestResult;
import com.tradingbot.backtest.dto.CandleData;
import com.tradingbot.backtest.engine.BacktestContext;

import java.util.List;

/**
 * Strategy interface for backtesting.
 *
 * This interface is COMPLETELY DECOUPLED from the live trading strategy interface
 * (TradingStrategy) to ensure isolation and prevent any accidental impact on
 * live trading behavior.
 *
 * Implementations should be stateless - all state is maintained in BacktestContext.
 */
public interface BacktestStrategy {

    /**
     * Initialize the strategy with the given request parameters.
     * Called once before processing any candles.
     *
     * @param request The backtest request configuration
     * @param context The backtest context to initialize positions/state
     */
    void initialize(BacktestRequest request, BacktestContext context);

    /**
     * Process a new candle and make trading decisions.
     *
     * This method is called for each candle in the historical data sequence.
     * The strategy should update the context with any entries, exits, or
     * position modifications.
     *
     * @param candle The current candle being processed
     * @param context The backtest context containing current state
     * @param historicalCandles All available candles up to this point (for lookback)
     */
    void onCandle(CandleData candle, BacktestContext context, List<CandleData> historicalCandles);

    /**
     * Called when a strategy restart is triggered (e.g., after a stop-loss exit).
     *
     * The engine handles the "Fast Forward" mechanism to align with the nearest
     * 5-minute candle boundary. This method allows the strategy to set up
     * new positions after restart.
     *
     * @param candle The candle at which restart occurs (aligned to 5-minute boundary)
     * @param context The backtest context
     */
    void onRestart(CandleData candle, BacktestContext context);

    /**
     * Called at market close to square off any open positions.
     *
     * @param lastCandle The final candle of the day
     * @param context The backtest context
     */
    void onMarketClose(CandleData lastCandle, BacktestContext context);

    /**
     * Get the strategy name for identification.
     *
     * @return Strategy name
     */
    String getStrategyName();

    /**
     * Check if the strategy supports restart mechanism.
     *
     * @return true if restarts are supported
     */
    default boolean supportsRestart() {
        return true;
    }

    /**
     * Get the required candle interval for this strategy.
     * Default is "5minute".
     *
     * @return Candle interval string
     */
    default String getRequiredInterval() {
        return "5minute";
    }
}

