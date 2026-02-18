package com.tradingbot.backtest.engine;

import com.tradingbot.backtest.adapter.BacktestExitContextBuilder;
import com.tradingbot.backtest.adapter.BacktestLegAdapter;
import com.tradingbot.backtest.dto.CandleData;
import com.tradingbot.service.strategy.monitoring.PositionMonitorV2;
import com.tradingbot.service.strategy.monitoring.exit.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Evaluates exit strategies using the same logic as live/paper trading.
 * <p>
 * This evaluator bridges the gap between backtest candle-based simulation and
 * the tick-based exit strategy framework used in live trading. It ensures
 * that backtest exit logic is IDENTICAL to live trading.
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li>Uses the same ExitStrategy implementations as live trading</li>
 *   <li>Simulates tick-by-tick evaluation from candle OHLC data</li>
 *   <li>Supports points-based, premium-based, trailing stop, and time-based exits</li>
 *   <li>Maintains BigDecimal precision for backtest reporting</li>
 * </ul>
 *
 * <h2>Tick Simulation Order</h2>
 * <p>
 * For realistic worst-case evaluation, candle OHLC is simulated in different
 * order based on candle direction:
 * <ul>
 *   <li>Bullish (Close > Open): O → L → H → C (worst case for shorts)</li>
 *   <li>Bearish (Close < Open): O → H → L → C (worst case for longs)</li>
 * </ul>
 *
 * @see ExitStrategy
 * @see ExitContext
 */
@Slf4j
public class BacktestExitStrategyEvaluator {

    /** List of exit strategies, sorted by priority */
    @Getter
    private final List<ExitStrategy> exitStrategies;

    /** Backtest context for state management */
    private final BacktestContext context;

    /** Context builder for creating ExitContext instances */
    private BacktestExitContextBuilder contextBuilder;

    /** Position direction for P&L calculation */
    private final PositionMonitorV2.PositionDirection direction;

    /** Last evaluation result */
    @Getter
    private ExitResult lastResult;

    /** Simulated current time for time-based exits */
    @Getter
    private LocalTime currentSimulatedTime;

    /**
     * Creates an evaluator with the specified strategies.
     *
     * @param exitStrategies list of exit strategies to evaluate
     * @param context backtest context
     * @param direction position direction (typically SHORT for SELL straddles)
     */
    public BacktestExitStrategyEvaluator(List<ExitStrategy> exitStrategies,
                                          BacktestContext context,
                                          PositionMonitorV2.PositionDirection direction) {
        // Sort strategies by priority (lower = higher priority)
        this.exitStrategies = new ArrayList<>(exitStrategies);
        this.exitStrategies.sort(Comparator.comparingInt(ExitStrategy::getPriority));
        this.context = context;
        this.direction = direction;
        rebuildContextBuilder();
    }

    /**
     * Rebuilds the context builder from current positions.
     * <p>
     * This should be called after positions change (entry/exit).
     */
    public void rebuildContextBuilder() {
        this.contextBuilder = new BacktestExitContextBuilder(context, direction);
    }

    /**
     * Evaluates exit conditions using candle OHLC data.
     * <p>
     * This method simulates tick-by-tick evaluation by checking exit conditions
     * at each OHLC price point. The order of evaluation depends on candle direction
     * to simulate worst-case scenarios.
     *
     * @param candle the candle data to evaluate
     * @param estimatedPremium current estimated combined premium
     * @return ExitResult indicating if exit should occur
     */
    public ExitResult evaluateCandle(CandleData candle, BigDecimal estimatedPremium) {
        if (!context.isPositionActive() || contextBuilder.getLegsCount() == 0) {
            return ExitResult.noExit();
        }

        // Update simulated time for time-based exits
        this.currentSimulatedTime = candle.getTimestamp().toLocalTime();

        // Get OHLC price sequence based on candle direction
        BigDecimal[] priceSequence = getCandlePriceSequence(candle);

        // Evaluate each price tick
        for (BigDecimal price : priceSequence) {
            // Calculate per-leg premium (simplified: divide by leg count)
            BigDecimal perLegPremium = estimatedPremium.divide(
                BigDecimal.valueOf(Math.max(1, contextBuilder.getLegsCount())),
                4, RoundingMode.HALF_UP
            );

            // Update leg prices
            for (BacktestLegAdapter adapter : contextBuilder.getLegAdapters()) {
                adapter.setCurrentPriceFromBigDecimal(perLegPremium);
            }

            // Build context with updated prices
            ExitContext ctx = contextBuilder.buildWithPnL();

            // Check time-based exit first
            ExitResult timeResult = checkTimeBasedExit(ctx);
            if (timeResult.requiresAction()) {
                this.lastResult = timeResult;
                return timeResult;
            }

            // Evaluate strategies in priority order
            for (ExitStrategy strategy : exitStrategies) {
                if (strategy instanceof TimeBasedForcedExitStrategy) {
                    // Already handled above with simulated time
                    continue;
                }

                if (!strategy.isEnabled(ctx)) {
                    continue;
                }

                ExitResult result = strategy.evaluate(ctx);
                if (result.requiresAction()) {
                    log.debug("Exit triggered by {} at price {}: {}",
                        strategy.getName(), price, result.getExitReason());
                    this.lastResult = result;
                    return result;
                }
            }
        }

        this.lastResult = ExitResult.noExit();
        return ExitResult.noExit();
    }

    /**
     * Evaluates exit conditions with a specific combined premium value.
     * <p>
     * This is a simplified evaluation that doesn't simulate OHLC ticks.
     *
     * @param combinedPremium current combined premium of all legs
     * @param simulatedTime current simulated time
     * @return ExitResult indicating if exit should occur
     */
    public ExitResult evaluate(BigDecimal combinedPremium, LocalTime simulatedTime) {
        if (!context.isPositionActive() || contextBuilder.getLegsCount() == 0) {
            return ExitResult.noExit();
        }

        this.currentSimulatedTime = simulatedTime;

        // Calculate per-leg premium
        BigDecimal perLegPremium = combinedPremium.divide(
            BigDecimal.valueOf(Math.max(1, contextBuilder.getLegsCount())),
            4, RoundingMode.HALF_UP
        );

        // Update leg prices
        for (BacktestLegAdapter adapter : contextBuilder.getLegAdapters()) {
            adapter.setCurrentPriceFromBigDecimal(perLegPremium);
        }

        // Build context and evaluate
        ExitContext ctx = contextBuilder.buildWithPnL();

        // Check time-based exit first
        ExitResult timeResult = checkTimeBasedExit(ctx);
        if (timeResult.requiresAction()) {
            this.lastResult = timeResult;
            return timeResult;
        }

        // Evaluate other strategies
        for (ExitStrategy strategy : exitStrategies) {
            if (strategy instanceof TimeBasedForcedExitStrategy) {
                continue;
            }

            if (!strategy.isEnabled(ctx)) {
                continue;
            }

            ExitResult result = strategy.evaluate(ctx);
            if (result.requiresAction()) {
                this.lastResult = result;
                return result;
            }
        }

        this.lastResult = ExitResult.noExit();
        return ExitResult.noExit();
    }

    /**
     * Checks time-based exit using simulated time.
     */
    private ExitResult checkTimeBasedExit(ExitContext ctx) {
        for (ExitStrategy strategy : exitStrategies) {
            if (strategy instanceof TimeBasedForcedExitStrategy timeStrategy) {
                if (timeStrategy.shouldForcedExit(currentSimulatedTime)) {
                    timeStrategy.triggerManually();
                    return ExitResult.exitAll("TIME_BASED_FORCED_EXIT @ " + currentSimulatedTime);
                }
            }
        }
        return ExitResult.noExit();
    }

    /**
     * Gets the OHLC price sequence based on candle direction.
     * <p>
     * For worst-case evaluation:
     * - Bullish candle (Short position): O → L → H → C (SL may hit at High)
     * - Bearish candle (Short position): O → H → L → C (Target may hit at Low)
     *
     * @param candle the candle data
     * @return array of prices in evaluation order
     */
    private BigDecimal[] getCandlePriceSequence(CandleData candle) {
        boolean isBullish = candle.getClose().compareTo(candle.getOpen()) >= 0;

        if (direction == PositionMonitorV2.PositionDirection.SHORT) {
            // For SHORT: bullish candles are adverse (price went up = loss)
            if (isBullish) {
                // Check worst case first: O → L → H → C
                // H is worst for shorts (highest premium = biggest loss)
                return new BigDecimal[] {
                    candle.getOpen(),
                    candle.getLow(),
                    candle.getHigh(),
                    candle.getClose()
                };
            } else {
                // Bearish candle: O → H → L → C
                return new BigDecimal[] {
                    candle.getOpen(),
                    candle.getHigh(),
                    candle.getLow(),
                    candle.getClose()
                };
            }
        } else {
            // For LONG: bearish candles are adverse
            if (isBullish) {
                return new BigDecimal[] {
                    candle.getOpen(),
                    candle.getLow(),
                    candle.getHigh(),
                    candle.getClose()
                };
            } else {
                return new BigDecimal[] {
                    candle.getOpen(),
                    candle.getHigh(),
                    candle.getLow(),
                    candle.getClose()
                };
            }
        }
    }

    /**
     * Resets all stateful strategies (e.g., trailing stop, time-based).
     * <p>
     * This should be called after a restart to reset strategy state.
     */
    public void resetStrategies() {
        for (ExitStrategy strategy : exitStrategies) {
            if (strategy instanceof TrailingStopLossStrategy trailingStop) {
                trailingStop.reset();
            } else if (strategy instanceof TimeBasedForcedExitStrategy timeStrategy) {
                timeStrategy.reset();
            }
        }
        rebuildContextBuilder();
    }

    /**
     * Gets the current cumulative P&L.
     *
     * @return cumulative P&L in points
     */
    public double getCurrentCumulativePnL() {
        return contextBuilder.calculateCumulativePnL();
    }

    /**
     * Gets the current combined LTP.
     *
     * @return combined LTP
     */
    public double getCurrentCombinedLTP() {
        return contextBuilder.calculateCombinedLTP();
    }
}

