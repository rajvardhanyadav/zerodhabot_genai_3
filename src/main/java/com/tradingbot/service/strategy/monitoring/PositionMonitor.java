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
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Position monitor for tracking individual legs of a strategy
 */
@Slf4j
@Data
public class PositionMonitor {

    // Use configurable cumulative thresholds (provided via constructor arguments)
    private final BigDecimal cumulativeTargetPointsBD;
    private final BigDecimal cumulativeStopPointsBD;
    private static final String EXIT_REASON_CUMULATIVE_TARGET = "CUMULATIVE_TARGET_HIT (Signal: %s points)";
    private static final String EXIT_REASON_CUMULATIVE_STOPLOSS = "CUMULATIVE_STOPLOSS_HIT (Signal: %s points)";
    private static final String EXIT_REASON_PRICE_DIFF_INDIVIDUAL = "PRICE_DIFF_INDIVIDUAL (Leg: %s, Diff: %.2f points)";

    public enum PositionDirection {
        LONG,  // e.g., BUY ATM straddle
        SHORT  // e.g., SELL ATM straddle
    }

    private final String executionId;
    private final Map<String, LegMonitor> legsBySymbol = new ConcurrentHashMap<>();
    private final Map<Long, LegMonitor> legsByInstrumentToken = new ConcurrentHashMap<>();
    private final double stopLossPoints;
    private final double targetPoints;
    /**
     * Direction of the overall strategy:
     * LONG  -> profit when prices move up from entry (e.g., long options)
     * SHORT -> profit when prices move down from entry (e.g., short options)
     */
    private final PositionDirection direction;
    @Setter
    private Consumer<String> exitCallback;
    @Setter
    private BiConsumer<String, String> individualLegExitCallback; // (legSymbol, reason)
    @Getter
    private volatile boolean active = true;
    private String exitReason;

    // Backwards-compatible constructor defaults to LONG behaviour (used by existing tests/strategies)
    public PositionMonitor(String executionId, double stopLossPoints, double targetPoints) {
        this(executionId, stopLossPoints, targetPoints, PositionDirection.LONG);
    }

    public PositionMonitor(String executionId,
                           double stopLossPoints,
                           double targetPoints,
                           PositionDirection direction) {
        this.executionId = executionId;
        this.stopLossPoints = stopLossPoints;
        this.targetPoints = targetPoints;
        this.direction = direction != null ? direction : PositionDirection.LONG;
        // Initialize BigDecimal thresholds; if provided values are non-positive, fall back to 2.0
        double effectiveTarget = targetPoints > 0 ? targetPoints : 2.0;
        double effectiveStop = stopLossPoints > 0 ? stopLossPoints : 2.0;
        this.cumulativeTargetPointsBD = BigDecimal.valueOf(effectiveTarget).setScale(8, RoundingMode.HALF_UP);
        this.cumulativeStopPointsBD = BigDecimal.valueOf(effectiveStop).setScale(8, RoundingMode.HALF_UP);
    }

    /**
     * Add a leg to monitor
     */
    public void addLeg(String orderId, String symbol, long instrumentToken,
                       double entryPrice, int quantity, String type) {
        // Accept double entryPrice for backwards compatibility, but store as BigDecimal
        LegMonitor leg = new LegMonitor(orderId, symbol, instrumentToken,
                                        BigDecimal.valueOf(entryPrice), quantity, type);
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

        log.info("Updating prices with {} ticks", ticks.size());

        updateLegPrices(ticks);

        // First check cumulative directional points across all legs and trigger full exit
        if (checkAndTriggerCumulativeExit()) {
            return; // Exit if all legs are closed due to cumulative target/stoploss
        }

        // Note: individual leg exit-on-loss logic has been intentionally left out to enforce
        // cumulative-only exit behaviour as per updated requirements.
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
                leg.setCurrentPrice(BigDecimal.valueOf(e.getValue()));
//                BigDecimal cumulative = computeCumulativeDirectionalPoints();
//                log.info("Execution {}: Token={} Symbol={} updated price={} -> cumulativePoints={}",
//                        executionId, e.getKey(), leg.getSymbol(), leg.getCurrentPrice().toPlainString(), cumulative.toPlainString());
            }
        }
        if (checkAndTriggerCumulativeExit()) {
            return;
        }
    }

    private void updateLegPrices(ArrayList<Tick> ticks) {
        for (Tick tick : ticks) {
            LegMonitor leg = legsByInstrumentToken.get(tick.getInstrumentToken());
            if (leg != null) {
                leg.setCurrentPrice(BigDecimal.valueOf(tick.getLastTradedPrice()));
                // Log the update and the cumulative directional points after this token update
//                BigDecimal cumulative = computeCumulativeDirectionalPoints();
                log.info("Symbol={} entry price={} updated price={}",leg.getSymbol(), leg.getEntryPrice().toPlainString(), leg.getCurrentPrice().toPlainString());
            }
        }
    }

    /**
     * Compute cumulative directional points across all monitored legs (helper used for logging).
     */
    private BigDecimal computeCumulativeDirectionalPoints() {
        BigDecimal cumulative = BigDecimal.ZERO;
        for (LegMonitor leg : legsBySymbol.values()) {
            BigDecimal rawDiff = leg.getCurrentPrice().subtract(leg.getEntryPrice());
            BigDecimal directionalDiff = (direction == PositionDirection.SHORT) ? rawDiff.negate() : rawDiff;
            cumulative = cumulative.add(directionalDiff);
        }
        return cumulative.setScale(8, RoundingMode.HALF_UP);
    }

    private boolean checkAndTriggerAllLegsExit() {
        // Deprecated: retained for reference but not used.
        return false;
    }

    /**
     * Compute cumulative directional points across all legs and trigger a full exit when
     * the configured target or stoploss is reached.
     *
     * @return true if an exit was triggered
     */
    private boolean checkAndTriggerCumulativeExit() {
        BigDecimal cumulative = computeCumulativeDirectionalPoints();

        // Log cumulative on each evaluation for observability
        log.info("Evaluated cumulativePoints={} (target={}, stop={})",
                cumulative.toPlainString(), cumulativeTargetPointsBD.toPlainString(), cumulativeStopPointsBD.toPlainString());

        // Check target hit (using configured cumulative target)
        if (cumulative.compareTo(cumulativeTargetPointsBD) >= 0) {
            log.warn("Cumulative target hit for execution {}: cumulative={} points, target={} - Closing ALL legs",
                    executionId, cumulative.toPlainString(), cumulativeTargetPointsBD.toPlainString());
            triggerExitAllLegs(String.format(EXIT_REASON_CUMULATIVE_TARGET, cumulative.toPlainString()));
            return true;
        }

        // Check stoploss hit (cumulative negative direction, using configured stop)
        if (cumulative.compareTo(cumulativeStopPointsBD.negate()) <= 0) {
            log.warn("Cumulative stoploss hit for execution {}: cumulative={} points, stopLoss={} - Closing ALL legs",
                    executionId, cumulative.toPlainString(), cumulativeStopPointsBD.toPlainString());
            triggerExitAllLegs(String.format(EXIT_REASON_CUMULATIVE_STOPLOSS, cumulative.toPlainString()));
            return true;
        }

        return false;
    }

    private void checkAndTriggerIndividualLegExits() {
        // Intentionally left blank: individual-leg exits by per-leg point-diff are disabled to
        // enforce cumulative-only exit behaviour (all legs exit together on cumulative thresholds).
    }

    /**
     * Trigger exit for all legs
     */
    private void triggerExitAllLegs(String reason) {
        if (!active) {
            return;
        }

        active = false;
        exitReason = reason;

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
        private final BigDecimal entryPrice;
        private final int quantity;
        private final String type; // CE or PE
        private volatile BigDecimal currentPrice;
        private volatile BigDecimal pnl;

        public LegMonitor(String orderId, String symbol, long instrumentToken,
                         BigDecimal entryPrice, int quantity, String type) {
            this.orderId = orderId;
            this.symbol = symbol;
            this.instrumentToken = instrumentToken;
            this.entryPrice = entryPrice.setScale(8, RoundingMode.HALF_UP);
            this.quantity = quantity;
            this.type = type;
            this.currentPrice = this.entryPrice;
            this.pnl = BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
    }
}
