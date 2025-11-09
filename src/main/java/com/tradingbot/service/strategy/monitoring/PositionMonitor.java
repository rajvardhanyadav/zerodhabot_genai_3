package com.tradingbot.service.strategy.monitoring;

import com.zerodhatech.models.Tick;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Position monitor for tracking individual legs of a strategy
 */
@Slf4j
@Data
public class PositionMonitor {

    private final String executionId;
    private final Map<String, LegMonitor> legs = new ConcurrentHashMap<>();
    private final double stopLossPoints;
    private final double targetPoints;
    private Consumer<String> exitCallback;
    private volatile boolean active = true;
    private String exitReason;

    public PositionMonitor(String executionId, double stopLossPoints, double targetPoints) {
        this.executionId = executionId;
        this.stopLossPoints = stopLossPoints;
        this.targetPoints = targetPoints;
    }

    /**
     * Add a leg to monitor
     */
    public void addLeg(String orderId, String symbol, long instrumentToken,
                       double entryPrice, int quantity, String type) {
        LegMonitor leg = new LegMonitor(orderId, symbol, instrumentToken,
                                        entryPrice, quantity, type);
        legs.put(symbol, leg);
        log.info("Added leg to monitor: {} at entry price: {}", symbol, entryPrice);
    }

    /**
     * Update price for a leg from WebSocket tick
     */
    public void updatePrice(long instrumentToken, double ltp) {
        if (!active) {
            return;
        }

        for (LegMonitor leg : legs.values()) {
            if (leg.instrumentToken == instrumentToken) {
                leg.currentPrice = ltp;
                double pnl = calculateLegPnL(leg);
                leg.pnl = pnl;

                // Check stop loss
                double loss = leg.entryPrice - ltp;
                if (loss >= stopLossPoints) {
                    log.warn("Stop loss hit for {}: Entry={}, Current={}, Loss={} points",
                             leg.symbol, leg.entryPrice, ltp, loss);
                    triggerExit("STOP_LOSS", leg.symbol);
                    return;
                }

                // Check target
                double profit = ltp - leg.entryPrice;
                if (profit >= targetPoints) {
                    log.info("Target hit for {}: Entry={}, Current={}, Profit={} points",
                             leg.symbol, leg.entryPrice, ltp, profit);
                    triggerExit("TARGET", leg.symbol);
                    return;
                }

                log.debug("Price update for {}: {} (P&L: {})", leg.symbol, ltp, pnl);
            }
        }
    }

    /**
     * Calculate P&L for a leg
     */
    private double calculateLegPnL(LegMonitor leg) {
        return (leg.currentPrice - leg.entryPrice) * leg.quantity;
    }

    /**
     * Calculate total P&L across all legs
     */
    public double getTotalPnL() {
        return legs.values().stream()
                .mapToDouble(this::calculateLegPnL)
                .sum();
    }

    /**
     * Trigger exit for all legs
     */
    private void triggerExit(String reason, String triggerLeg) {
        if (!active) {
            return;
        }

        active = false;
        exitReason = reason + " (Triggered by: " + triggerLeg + ")";

        log.warn("Triggering exit for execution {} - Reason: {}", executionId, exitReason);

        if (exitCallback != null) {
            exitCallback.accept(exitReason);
        }
    }

    /**
     * Stop monitoring
     */
    public void stop() {
        active = false;
        log.info("Stopped monitoring for execution: {}", executionId);
    }

    /**
     * Check if monitoring is active
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Get all legs
     */
    public List<LegMonitor> getLegs() {
        return List.copyOf(legs.values());
    }

    /**
     * Individual leg monitor
     */
    @Data
    public static class LegMonitor {
        private final String orderId;
        private final String symbol;
        private final long instrumentToken;
        private final double entryPrice;
        private final int quantity;
        private final String type; // CE or PE
        private volatile double currentPrice;
        private volatile double pnl;

        public LegMonitor(String orderId, String symbol, long instrumentToken,
                         double entryPrice, int quantity, String type) {
            this.orderId = orderId;
            this.symbol = symbol;
            this.instrumentToken = instrumentToken;
            this.entryPrice = entryPrice;
            this.quantity = quantity;
            this.type = type;
            this.currentPrice = entryPrice;
            this.pnl = 0.0;
        }
    }
}

