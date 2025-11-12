package com.tradingbot.service.strategy.monitoring;

import com.zerodhatech.models.Tick;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
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
    @Setter
    private Consumer<String> exitCallback;
    @Setter
    private BiConsumer<String, String> individualLegExitCallback; // (legSymbol, reason)
    @Getter
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
     * Update price method with individual price difference monitoring.
     * Processes multiple ticks and checks price difference thresholds:
     * - If any leg has difference >= 3 points (profit): close all legs
     * - If any leg has difference <= -1.5 points (loss): close that individual leg
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

                    // Calculate signed price difference (positive = profit, negative = loss)
                    double priceDifference = ltp - leg.entryPrice;

                    log.debug("Price diff check for {}: Entry={}, Current={}, Diff={}",
                             leg.symbol, leg.entryPrice, ltp, priceDifference);

                    // Track maximum difference
                    if (priceDifference > maxDifference) {
                        maxDifference = priceDifference;
                        maxDiffLegSymbol = leg.symbol;
                    }

                    // Mark legs that need to be closed (difference <= -1.5, i.e., loss of 1.5+ points)
                    if (priceDifference <= -1.5) {
                        legsToClose.add(leg.symbol);
                        log.info("Leg {} marked for closure (loss): Diff={} points", leg.symbol, priceDifference);
                    }

                    break; // Found matching leg, move to next tick
                }
            }
        }

        // Check if we need to close all legs (max difference >= 3, i.e., profit of 3+ points)
        if (maxDifference >= 3.0) {
            log.warn("Price difference threshold (3+) hit for {}: Diff={} points - Closing ALL legs",
                     maxDiffLegSymbol, maxDifference);
            triggerExitAllLegs(maxDiffLegSymbol);
            return;
        }

        // Close individual legs if difference <= -1.5 (loss)
        if (!legsToClose.isEmpty()) {
            for (String legSymbol : legsToClose) {
                LegMonitor leg = legs.get(legSymbol);
                if (leg != null) {
                    double diff = leg.currentPrice - leg.entryPrice;
                    log.warn("Price difference threshold (-1.5 or less) hit for {}: Entry={}, Current={}, Diff={} points - Closing individual leg",
                             legSymbol, leg.entryPrice, leg.currentPrice, diff);
                    triggerIndividualLegExit(legSymbol, diff);
                }
            }
        }
    }

    /**
     * Trigger exit for all legs
     */
    private void triggerExitAllLegs(String triggerLeg) {
        if (!active) {
            return;
        }

        active = false;
        exitReason = "PRICE_DIFF_ALL_LEGS (Triggered by: " + triggerLeg + ")";

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
