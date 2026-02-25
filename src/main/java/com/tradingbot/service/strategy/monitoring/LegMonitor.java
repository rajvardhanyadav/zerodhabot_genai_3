package com.tradingbot.service.strategy.monitoring;

import lombok.Getter;

/**
 * Individual leg monitor - optimized for HFT with primitive doubles.
 * <p>
 * Represents a single option contract within a multi-leg strategy.
 * Uses volatile for currentPrice to ensure visibility across threads without synchronization.
 * <p>
 * Each leg tracks:
 * <ul>
 *   <li>Order ID for exit operations</li>
 *   <li>Symbol and instrument token for price lookups</li>
 *   <li>Entry price and current price for P&amp;L calculation</li>
 *   <li>Quantity and type (CE/PE) for position management</li>
 * </ul>
 *
 * <h2>HFT Optimizations</h2>
 * <ul>
 *   <li>Primitive double for prices (no BigDecimal overhead)</li>
 *   <li>Volatile currentPrice for thread-safe updates without synchronization</li>
 *   <li>Final fields for immutable properties</li>
 * </ul>
 */
@Getter
public class LegMonitor {

    private final String orderId;
    private final String symbol;
    private final long instrumentToken;
    // HFT: volatile because entryPrice is written by exit handler thread (leg replacement)
    // and read by WebSocket tick thread (P&L calculation)
    private volatile double entryPrice;
    private final int quantity;
    private final String type; // CE or PE

    /**
     * Per-leg direction multiplier for P&L calculation.
     * <p>
     * For strategies where all legs share the same direction (e.g., straddle: all SELL),
     * this defaults to 1.0 and the monitor's overall directionMultiplier handles the sign.
     * <p>
     * For mixed-direction strategies (e.g., strangle: SELL main + BUY hedge),
     * hedge legs use -1.0 to invert their contribution relative to the monitor direction.
     * <p>
     * Effective P&L per leg = (currentPrice - entryPrice) * monitor.directionMultiplier * legDirectionMultiplier
     */
    private final double legDirectionMultiplier;

    // Volatile double for thread-safe price updates without synchronization overhead
    volatile double currentPrice;

    /**
     * Creates a new leg monitor with default direction multiplier (1.0).
     *
     * @param orderId unique order identifier
     * @param symbol trading symbol (e.g., "NIFTY24350CE")
     * @param instrumentToken Zerodha instrument token
     * @param entryPrice entry price for this leg
     * @param quantity number of contracts
     * @param type leg type ("CE" or "PE")
     */
    public LegMonitor(String orderId, String symbol, long instrumentToken,
                     double entryPrice, int quantity, String type) {
        this(orderId, symbol, instrumentToken, entryPrice, quantity, type, 1.0);
    }

    /**
     * Creates a new leg monitor with explicit direction multiplier.
     *
     * @param orderId unique order identifier
     * @param symbol trading symbol (e.g., "NIFTY24350CE")
     * @param instrumentToken Zerodha instrument token
     * @param entryPrice entry price for this leg
     * @param quantity number of contracts
     * @param type leg type ("CE" or "PE")
     * @param legDirectionMultiplier per-leg direction multiplier (1.0 for same as monitor, -1.0 for opposite)
     */
    public LegMonitor(String orderId, String symbol, long instrumentToken,
                     double entryPrice, int quantity, String type, double legDirectionMultiplier) {
        this.orderId = orderId;
        this.symbol = symbol;
        this.instrumentToken = instrumentToken;
        this.entryPrice = entryPrice;
        this.quantity = quantity;
        this.type = type;
        this.legDirectionMultiplier = legDirectionMultiplier;
        this.currentPrice = entryPrice;
    }

    /**
     * Get current price (volatile read).
     *
     * @return current LTP for this leg
     */
    public double getCurrentPrice() {
        return currentPrice;
    }

    /**
     * Set current price (volatile write).
     * <p>
     * Thread-safe for concurrent updates from WebSocket thread.
     *
     * @param price new current price
     */
    public void setCurrentPrice(double price) {
        this.currentPrice = price;
    }

    /**
     * Set entry price for this leg.
     * <p>
     * Used during leg replacement to reset the entry price to the current LTP
     * when a paired leg is being replaced.
     *
     * @param price new entry price
     */
    public void setEntryPrice(double price) {
        this.entryPrice = price;
    }

    /**
     * Get P&L for this leg using primitive arithmetic.
     *
     * @return raw P&L = (currentPrice - entryPrice) * quantity
     */
    public double getPnl() {
        return (currentPrice - entryPrice) * quantity;
    }

    /**
     * Get P&L per unit (without quantity multiplier).
     *
     * @return per-unit P&L = currentPrice - entryPrice
     */
    public double getUnitPnl() {
        return currentPrice - entryPrice;
    }
}

