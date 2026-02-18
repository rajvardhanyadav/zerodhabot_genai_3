package com.tradingbot.backtest.adapter;

import com.tradingbot.backtest.engine.BacktestContext;
import com.tradingbot.backtest.engine.BacktestContext.SimulatedPosition;
import com.tradingbot.service.strategy.monitoring.PositionMonitorV2;
import com.tradingbot.service.strategy.monitoring.exit.ExitContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builder that creates ExitContext instances from BacktestContext state.
 * <p>
 * This builder bridges the gap between backtest infrastructure (using BigDecimal)
 * and the exit strategy framework (using primitives for HFT performance).
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Convert BacktestContext configuration to ExitContext parameters</li>
 *   <li>Create BacktestLegAdapter array from SimulatedPositions</li>
 *   <li>Calculate premium-based thresholds (target/SL levels)</li>
 *   <li>Handle both points-based and percentage-based modes</li>
 * </ul>
 *
 * @see ExitContext
 * @see BacktestContext
 */
public class BacktestExitContextBuilder {

    private final BacktestContext context;
    private final PositionMonitorV2.PositionDirection direction;
    private final double directionMultiplier;
    private BacktestLegAdapter[] legAdapters;
    private int legsCount;

    /**
     * Creates a builder for the given BacktestContext.
     *
     * @param context the BacktestContext to build from
     * @param direction position direction (typically SHORT for SELL straddles)
     */
    public BacktestExitContextBuilder(BacktestContext context, PositionMonitorV2.PositionDirection direction) {
        this.context = context;
        this.direction = direction;
        this.directionMultiplier = (direction == PositionMonitorV2.PositionDirection.SHORT) ? -1.0 : 1.0;
        buildLegAdapters();
    }

    /**
     * Builds BacktestLegAdapter array from current positions.
     */
    private void buildLegAdapters() {
        Map<String, SimulatedPosition> positions = context.getOpenPositions();
        if (positions == null || positions.isEmpty()) {
            this.legAdapters = new BacktestLegAdapter[0];
            this.legsCount = 0;
            return;
        }

        List<BacktestLegAdapter> adapters = new ArrayList<>(positions.size());
        for (SimulatedPosition position : positions.values()) {
            adapters.add(new BacktestLegAdapter(position));
        }
        this.legAdapters = adapters.toArray(new BacktestLegAdapter[0]);
        this.legsCount = this.legAdapters.length;
    }

    /**
     * Updates the current prices of all leg adapters.
     * <p>
     * This method is called during tick simulation to propagate
     * simulated prices to the leg adapters.
     *
     * @param prices map of symbol to current price
     */
    public void updatePrices(Map<String, BigDecimal> prices) {
        for (BacktestLegAdapter adapter : legAdapters) {
            BigDecimal price = prices.get(adapter.getSymbol());
            if (price != null) {
                adapter.setCurrentPriceFromBigDecimal(price);
            }
        }
    }

    /**
     * Updates all legs to the same price (for simplified simulation).
     *
     * @param price the price to set for all legs
     */
    public void updateAllPrices(BigDecimal price) {
        for (BacktestLegAdapter adapter : legAdapters) {
            adapter.setCurrentPriceFromBigDecimal(price);
        }
    }

    /**
     * Builds an ExitContext for strategy evaluation.
     *
     * @return ExitContext with all parameters set from BacktestContext
     */
    public ExitContext build() {
        // Extract configuration from context
        double targetPoints = safeDouble(context.getTargetPoints(), 2.5);
        double stopLossPoints = safeDouble(context.getStopLossPoints(), 4.0);
        double entryPremium = safeDouble(context.getEntryPremium(), 0.0);

        // Calculate premium-based thresholds
        double targetDecayPct = safeDouble(context.getTargetDecayPct(), 5.0) / 100.0;
        double stopLossExpansionPct = safeDouble(context.getStopLossExpansionPct(), 10.0) / 100.0;

        double targetPremiumLevel = entryPremium * (1.0 - targetDecayPct);
        double stopLossPremiumLevel = entryPremium * (1.0 + stopLossExpansionPct);

        // Create ExitContext with calculated values
        // Note: Callbacks are null for backtest - we handle exits differently
        return new ExitContext(
            context.getBacktestId(),
            directionMultiplier,
            direction,
            targetPoints,
            stopLossPoints,
            entryPremium,
            targetPremiumLevel,
            stopLossPremiumLevel,
            legAdapters,  // BacktestLegAdapter extends LegMonitor
            legsCount,
            null,  // No individual leg exit callback for backtest
            null   // No leg replacement callback for backtest
        );
    }

    /**
     * Builds an ExitContext with pre-calculated cumulative PnL.
     *
     * @return ExitContext with cumulativePnL set
     */
    public ExitContext buildWithPnL() {
        ExitContext ctx = build();
        ctx.setCumulativePnL(calculateCumulativePnL());
        ctx.setCombinedLTP(calculateCombinedLTP());
        return ctx;
    }

    /**
     * Calculates cumulative P&L across all legs.
     *
     * @return cumulative P&L in points
     */
    public double calculateCumulativePnL() {
        double cumulative = 0.0;
        for (BacktestLegAdapter adapter : legAdapters) {
            cumulative += (adapter.getCurrentPrice() - adapter.getEntryPrice()) * directionMultiplier;
        }
        return cumulative;
    }

    /**
     * Calculates combined current LTP of all legs.
     *
     * @return combined LTP
     */
    public double calculateCombinedLTP() {
        double combined = 0.0;
        for (BacktestLegAdapter adapter : legAdapters) {
            combined += adapter.getCurrentPrice();
        }
        return combined;
    }

    /**
     * Gets the leg adapters for direct manipulation.
     *
     * @return array of BacktestLegAdapter
     */
    public BacktestLegAdapter[] getLegAdapters() {
        return legAdapters;
    }

    /**
     * Gets the number of active legs.
     *
     * @return leg count
     */
    public int getLegsCount() {
        return legsCount;
    }

    /**
     * Gets the direction multiplier (1.0 for LONG, -1.0 for SHORT).
     *
     * @return direction multiplier
     */
    public double getDirectionMultiplier() {
        return directionMultiplier;
    }

    /**
     * Safely converts BigDecimal to double with default value.
     */
    private static double safeDouble(BigDecimal value, double defaultValue) {
        return value != null ? value.doubleValue() : defaultValue;
    }
}


