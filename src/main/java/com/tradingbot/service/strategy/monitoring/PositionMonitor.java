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

    private static final double INDIVIDUAL_LEG_CLOSE_THRESHOLD = -1.5; // points
    private static final double ALL_LEGS_CLOSE_THRESHOLD = 3.0; // points
    private static final String EXIT_REASON_PRICE_DIFF_ALL_LEGS = "PRICE_DIFF_ALL_LEGS (Triggered by: %s)";
    private static final String EXIT_REASON_PRICE_DIFF_INDIVIDUAL = "PRICE_DIFF_INDIVIDUAL (Leg: %s, Diff: %.2f points)";

    private final String executionId;
    private final Map<String, LegMonitor> legsBySymbol = new ConcurrentHashMap<>();
    private final Map<Long, LegMonitor> legsByInstrumentToken = new ConcurrentHashMap<>();
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
        legsBySymbol.put(symbol, leg);
        legsByInstrumentToken.put(instrumentToken, leg);
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

        updateLegPrices(ticks);

        if (checkAndTriggerAllLegsExit()) {
            return; // Exit if all legs are closed
        }

        checkAndTriggerIndividualLegExits();
    }

    // --- New helper for historical replay ---
    /**
     * Update prices using a map of instrumentToken -> lastPrice and run threshold checks.
     */
    public void updateWithTokenPrices(Map<Long, Double> tokenPrices) {
        if (!active || tokenPrices == null || tokenPrices.isEmpty()) {
            return;
        }
        for (Map.Entry<Long, Double> e : tokenPrices.entrySet()) {
            LegMonitor leg = legsByInstrumentToken.get(e.getKey());
            if (leg != null && e.getValue() != null) {
                leg.setCurrentPrice(e.getValue());
                log.debug("Price updated for {}: {}", leg.getSymbol(), leg.getCurrentPrice());
            }
        }
        if (checkAndTriggerAllLegsExit()) {
            return;
        }
        checkAndTriggerIndividualLegExits();
    }

    private void updateLegPrices(ArrayList<Tick> ticks) {
        for (Tick tick : ticks) {
            LegMonitor leg = legsByInstrumentToken.get(tick.getInstrumentToken());
            if (leg != null) {
                leg.setCurrentPrice(tick.getLastTradedPrice());
                log.debug("Price updated for {}: {}", leg.getSymbol(), leg.getCurrentPrice());
            }
        }
    }

    private boolean checkAndTriggerAllLegsExit() {
        double maxDifference = Double.NEGATIVE_INFINITY;
        String triggerLegSymbol = null;

        for (LegMonitor leg : legsBySymbol.values()) {
            double priceDifference = leg.getCurrentPrice() - leg.getEntryPrice();
            if (priceDifference > maxDifference) {
                maxDifference = priceDifference;
                triggerLegSymbol = leg.getSymbol();
            }
        }

        if (maxDifference >= ALL_LEGS_CLOSE_THRESHOLD) {
            log.warn("Price difference threshold (3+) hit for {}: Diff={} points - Closing ALL legs",
                    triggerLegSymbol, maxDifference);
            triggerExitAllLegs(triggerLegSymbol);
            return true;
        }
        return false;
    }

    private void checkAndTriggerIndividualLegExits() {
        List<String> legsToClose = new ArrayList<>();
        for (LegMonitor leg : legsBySymbol.values()) {
            double priceDifference = leg.getCurrentPrice() - leg.getEntryPrice();
            if (priceDifference <= INDIVIDUAL_LEG_CLOSE_THRESHOLD) {
                legsToClose.add(leg.getSymbol());
                log.info("Leg {} marked for closure (loss): Diff={} points", leg.getSymbol(), priceDifference);
            }
        }

        for (String legSymbol : legsToClose) {
            LegMonitor leg = legsBySymbol.get(legSymbol);
            if (leg != null) {
                double diff = leg.getCurrentPrice() - leg.getEntryPrice();
                log.warn("Price difference threshold (-1.5 or less) hit for {}: Entry={}, Current={}, Diff={} points - Closing individual leg",
                        legSymbol, leg.getEntryPrice(), leg.getCurrentPrice(), diff);
                triggerIndividualLegExit(legSymbol, diff);
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
        exitReason = String.format(EXIT_REASON_PRICE_DIFF_ALL_LEGS, triggerLeg);

        log.warn("Triggering exit for execution {} - Reason: {}", executionId, exitReason);

        if (exitCallback != null) {
            exitCallback.accept(exitReason);
        }
    }

    /**
     * Trigger exit for an individual leg
     */
    private void triggerIndividualLegExit(String legSymbol, double priceDifference) {
        LegMonitor leg = legsBySymbol.get(legSymbol);
        if (leg == null) {
            log.warn("Cannot close leg {}: not found in monitor", legSymbol);
            return;
        }

        String exitReason = String.format(EXIT_REASON_PRICE_DIFF_INDIVIDUAL,
                                         legSymbol, priceDifference);

        log.warn("Triggering individual leg exit for {} in execution {} - Reason: {}",
                 legSymbol, executionId, exitReason);

        // Remove the leg from monitoring
        legsBySymbol.remove(legSymbol);
        legsByInstrumentToken.remove(leg.getInstrumentToken());

        // If individualLegExitCallback is set, use it; otherwise fall back to exitCallback
        if (individualLegExitCallback != null) {
            individualLegExitCallback.accept(legSymbol, exitReason);
        } else if (exitCallback != null) {
            // Fallback to full exit if individual callback not set
            log.warn("Individual leg exit callback not set, falling back to full exit");
            exitCallback.accept(exitReason);
        }

        // If no more legs remain, deactivate the monitor
        if (legsBySymbol.isEmpty()) {
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
        return List.copyOf(legsBySymbol.values());
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
