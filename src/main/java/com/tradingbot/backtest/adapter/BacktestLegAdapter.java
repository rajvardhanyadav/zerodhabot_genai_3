package com.tradingbot.backtest.adapter;

import com.tradingbot.backtest.engine.BacktestContext.SimulatedPosition;
import com.tradingbot.service.strategy.monitoring.LegMonitor;

import java.math.BigDecimal;

/**
 * Adapter that bridges backtest SimulatedPosition to LegMonitor for exit strategy evaluation.
 * <p>
 * This adapter enables the backtest module to reuse the same {@link com.tradingbot.service.strategy.monitoring.exit.ExitStrategy}
 * implementations used in live/paper trading, ensuring consistent exit logic across all trading modes.
 *
 * <h2>Design Principles</h2>
 * <ul>
 *   <li>Converts BigDecimal prices from SimulatedPosition to primitives for HFT strategies</li>
 *   <li>Maintains BigDecimal precision for backtest reporting</li>
 *   <li>Provides mutable current price for tick-by-tick simulation</li>
 * </ul>
 *
 * @see LegMonitor
 * @see SimulatedPosition
 */
public class BacktestLegAdapter extends LegMonitor {

    /** Reference to the original SimulatedPosition for BigDecimal access */
    private final SimulatedPosition position;

    /**
     * Creates a BacktestLegAdapter wrapping a SimulatedPosition.
     *
     * @param position the SimulatedPosition to wrap
     * @param orderId unique identifier for this leg (can be generated)
     */
    public BacktestLegAdapter(SimulatedPosition position, String orderId) {
        super(
            orderId,
            position.getTradingSymbol(),
            position.getInstrumentToken(),
            position.getEntryPrice().doubleValue(),
            position.getQuantity(),
            position.getOptionType()
        );
        this.position = position;
    }

    /**
     * Creates a BacktestLegAdapter with auto-generated order ID.
     *
     * @param position the SimulatedPosition to wrap
     */
    public BacktestLegAdapter(SimulatedPosition position) {
        this(position, "BT-" + position.getTradingSymbol() + "-" + System.nanoTime());
    }

    /**
     * Updates the current price from a BigDecimal value.
     * <p>
     * This method is used during candle simulation to set prices
     * from OHLC data while maintaining the primitive performance
     * required by exit strategies.
     *
     * @param price the new current price as BigDecimal
     */
    public void setCurrentPriceFromBigDecimal(BigDecimal price) {
        if (price != null) {
            super.setCurrentPrice(price.doubleValue());
        }
    }

    /**
     * Gets the current price as BigDecimal for backtest reporting.
     *
     * @return current price with full precision
     */
    public BigDecimal getCurrentPriceAsBigDecimal() {
        return BigDecimal.valueOf(getCurrentPrice());
    }

    /**
     * Gets the entry price as BigDecimal for backtest reporting.
     *
     * @return entry price with full precision
     */
    public BigDecimal getEntryPriceAsBigDecimal() {
        return position.getEntryPrice();
    }

    /**
     * Gets the underlying SimulatedPosition.
     *
     * @return the wrapped SimulatedPosition
     */
    public SimulatedPosition getPosition() {
        return position;
    }

    /**
     * Checks if this is a SELL position.
     *
     * @return true if transaction type is SELL
     */
    public boolean isSellPosition() {
        return "SELL".equalsIgnoreCase(position.getTransactionType());
    }

    /**
     * Gets the transaction type (BUY/SELL).
     *
     * @return transaction type string
     */
    public String getTransactionType() {
        return position.getTransactionType();
    }
}

