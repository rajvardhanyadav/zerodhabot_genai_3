package com.tradingbot.service.strategy.monitoring;

import com.zerodhatech.models.Tick;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

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
    private BiConsumer<String, String> individualLegExitCallback; // (legSymbol, reason)
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
                leg.pnl = calculateLegPnL(leg);

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

                log.debug("Price update for {}: {} (P&L: {})", leg.symbol, ltp, leg.pnl);
            }
        }
    }

    /**
     * Optimized update price method for batch tick processing with P&L difference check
     * Processes multiple ticks in a single call for better real-time performance.
     * Triggers exit when (total CE PnL - total PE PnL) >= 250 or <= -350.
     *
     * @param ticks ArrayList of Tick objects from WebSocket
     */
    public void updatePriceWithPnLDiffCheck(ArrayList<Tick> ticks) {
        if (!active || ticks == null || ticks.isEmpty()) {
            return;
        }

        // Performance optimization: Update all matching legs in a single pass
        boolean anyLegUpdated = false;
        double totalCePnl = 0.0;
        double totalPePnl = 0.0;

        for (Tick tick : ticks) {
            long tickInstrumentToken = tick.getInstrumentToken();
            double ltp = tick.getLastTradedPrice();

            // Quick lookup: Check if this instrument token matches any leg
            for (LegMonitor leg : legs.values()) {
                if (leg.instrumentToken == tickInstrumentToken) {
                    // Update price and recalculate P&L inline for performance
                    leg.currentPrice = ltp;
                    leg.pnl = (ltp - leg.entryPrice) * leg.quantity;
                    anyLegUpdated = true;
                    String legType = leg.type;
                    if (legType != null) {
                        if ("CE".equals(legType)) {
                            totalCePnl += leg.pnl;
                        } else if ("PE".equals(legType)) {
                            totalPePnl += leg.pnl;
                        }
                    }

                    log.debug("Updated {}: Type={}, LTP={}, P&L={}", leg.symbol,leg.type, ltp, leg.pnl);
                    break; // Found matching leg, move to next tick
                }
            }
        }

        // Only check exit conditions if at least one leg was updated
        if (!anyLegUpdated) {
            return;
        }

        double diff = totalCePnl + totalPePnl;
        log.debug("P&L - CE: {}, PE: {}, Diff: {}", totalCePnl, totalPePnl, diff);

        // Check exit thresholds
        if (diff >= 250.0) {
            log.warn("P&L difference upper threshold hit (diff={}): CE={}, PE={}",
                     diff, totalCePnl, totalPePnl);
            triggerExit("PNL_DIFF_UPPER", "CE/PE");
        } else if (diff <= -350.0) {
            log.warn("P&L difference lower threshold hit (diff={}): CE={}, PE={}",
                     diff, totalCePnl, totalPePnl);
            triggerExit("PNL_DIFF_LOWER", "CE/PE");
        }
    }

    /**
     * Update price method with individual price difference monitoring
     * Processes multiple ticks and checks price difference thresholds:
     * - If any leg has difference >= 4 points (profit): close all legs
     * - If any leg has difference <= -2 points (loss): close that individual leg
     *
     * Difference = currentPrice - entryPrice (signed value)
     *
     * @param ticks ArrayList of Tick objects from WebSocket
     */
    public void updatePriceWithDifferenceCheck(ArrayList<Tick> ticks) {
        if (!active || ticks == null || ticks.isEmpty()) {
            return;
        }

        // Track maximum difference found across all legs
        double maxDifference = Double.NEGATIVE_INFINITY;
        String maxDiffLegSymbol = null;
        List<String> legsToClose = new ArrayList<>();

        // Update prices and check differences
        for (Tick tick : ticks) {
            long tickInstrumentToken = tick.getInstrumentToken();
            double ltp = tick.getLastTradedPrice();

            // Find matching leg and update
            for (LegMonitor leg : legs.values()) {
                if (leg.instrumentToken == tickInstrumentToken) {
                    // Update price and P&L
                    leg.currentPrice = ltp;
                    //leg.pnl = (ltp - leg.entryPrice) * leg.quantity;

                    // Calculate signed price difference (positive = profit, negative = loss)
                    double priceDifference = ltp - leg.entryPrice;

                    log.debug("Price diff check for {}: Entry={}, Current={}, Diff={}",
                             leg.symbol, leg.entryPrice, ltp, priceDifference);

                    // Track maximum difference
                    if (priceDifference > maxDifference) {
                        maxDifference = priceDifference;
                        maxDiffLegSymbol = leg.symbol;
                    }

                    // Mark legs that need to be closed (difference <= -2, i.e., loss of 2+ points)
                    if (priceDifference <= -1.5) {
                        legsToClose.add(leg.symbol);
                        log.info("Leg {} marked for closure (loss): Diff={} points", leg.symbol, priceDifference);
                    }

                    break; // Found matching leg, move to next tick
                }
            }
        }

        // Check if we need to close all legs (max difference >= 4, i.e., profit of 4+ points)
        if (maxDifference >= 3.0) {
            log.warn("Price difference threshold (4+) hit for {}: Diff={} points - Closing ALL legs",
                     maxDiffLegSymbol, maxDifference);
            triggerExit("PRICE_DIFF_ALL_LEGS", maxDiffLegSymbol);
            return;
        }

        // Close individual legs if difference <= -2 (loss)
        if (!legsToClose.isEmpty()) {
            for (String legSymbol : legsToClose) {
                LegMonitor leg = legs.get(legSymbol);
                if (leg != null) {
                    double diff = leg.currentPrice - leg.entryPrice;
                    log.warn("Price difference threshold (-2 or less) hit for {}: Entry={}, Current={}, Diff={} points - Closing individual leg",
                             legSymbol, leg.entryPrice, leg.currentPrice, diff);
                    triggerIndividualLegExit(legSymbol, diff);
                }
            }
        }
    }

    /**
     * Set callback for individual leg exit
     * @param callback BiConsumer that takes (legSymbol, exitReason)
     */
    public void setIndividualLegExitCallback(BiConsumer<String, String> callback) {
        this.individualLegExitCallback = callback;
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
     * Trigger exit for an individual leg
     */
    private void triggerIndividualLegExit(String legSymbol, double priceDifference) {
        LegMonitor leg = legs.get(legSymbol);
        if (leg == null) {
            log.warn("Cannot close leg {}: not found in monitor", legSymbol);
            return;
        }

        String exitReason = String.format("PRICE_DIFF_INDIVIDUAL (Leg: %s, Diff: %.2f points)",
                                         legSymbol, priceDifference);

        log.warn("Triggering individual leg exit for {} in execution {} - Reason: {}",
                 legSymbol, executionId, exitReason);

        // Remove the leg from monitoring
        legs.remove(legSymbol);

        // If individualLegExitCallback is set, use it; otherwise fall back to exitCallback
        if (individualLegExitCallback != null) {
            individualLegExitCallback.accept(legSymbol, exitReason);
        } else if (exitCallback != null) {
            // Fallback to full exit if individual callback not set
            log.warn("Individual leg exit callback not set, falling back to full exit");
            exitCallback.accept(exitReason);
        }

        // If no more legs remain, deactivate the monitor
        if (legs.isEmpty()) {
            active = false;
            log.info("All legs closed for execution {}, deactivating monitor", executionId);
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
