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
    private double entryPrice;
    private final int quantity;
    private final String type; // CE or PE

    // Volatile double for thread-safe price updates without synchronization overhead
    volatile double currentPrice;

    /**
     * Creates a new leg monitor.
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
        this.orderId = orderId;
        this.symbol = symbol;
        this.instrumentToken = instrumentToken;
        this.entryPrice = entryPrice;
        this.quantity = quantity;
        this.type = type;
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

