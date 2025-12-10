package com.tradingbot.service.strategy.monitoring;

import com.zerodhatech.models.Tick;
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
 * High-performance position monitor for tracking individual legs of a strategy.
 * Optimized for HFT with minimal object allocation and fast primitive operations.
 *
 * HFT Optimizations:
 * - Primitive doubles for threshold checks (no BigDecimal overhead)
 * - Pre-computed direction multiplier for single multiplication
 * - ConcurrentHashMap for thread-safe leg lookups without locking
 * - Volatile fields for thread-safe price updates without synchronization
 * - Cached immutable list for getLegs() to avoid repeated allocations
 * - Indexed loop iteration to avoid iterator allocation
 *
 * Trailing Stop Loss:
 * - Tracks high-water mark (best cumulative P&L achieved)
 * - Stop loss level trails up as position becomes profitable
 * - Configurable activation threshold and trail distance
 */
@Slf4j
public class PositionMonitor {

    // Use primitive doubles for fast threshold checks - no BigDecimal overhead
    private final double cumulativeTargetPoints;
    private final double cumulativeStopPoints;

    // Pre-computed direction multiplier: 1.0 for LONG, -1.0 for SHORT
    private final double directionMultiplier;

    // ==================== TRAILING STOP LOSS CONFIGURATION ====================
    // HFT: All trailing stop fields are primitives for fast arithmetic

    /** Enable/disable trailing stop loss feature */
    @Getter
    private final boolean trailingStopEnabled;

    /**
     * Activation threshold: trailing stop activates when cumulative P&L >= this value.
     * Example: 1.0 means trailing starts after 1 point profit.
     */
    private final double trailingActivationPoints;

    /**
     * Trail distance: how far behind the high-water mark the stop trails.
     * Example: 0.5 means stop is always 0.5 points below peak profit.
     */
    private final double trailingDistancePoints;

    /**
     * High-water mark: best cumulative P&L achieved since activation.
     * Volatile for thread-safe updates from tick processing.
     */
    private volatile double highWaterMark = 0.0;

    /**
     * Current trailing stop level (dynamic).
     * Computed as: highWaterMark - trailingDistancePoints
     * Only active when highWaterMark >= trailingActivationPoints
     */
    private volatile double currentTrailingStopLevel = Double.NEGATIVE_INFINITY;

    /**
     * Flag indicating if trailing stop has been activated.
     * Once activated, it stays active for the life of the monitor.
     */
    private volatile boolean trailingStopActivated = false;

    // HFT: Pre-built exit reason prefixes to avoid String.format on hot path
    // Using StringBuilder with pre-computed components is faster than String.format
    private static final String EXIT_PREFIX_TARGET = "CUMULATIVE_TARGET_HIT (Signal: ";
    private static final String EXIT_PREFIX_STOPLOSS = "CUMULATIVE_STOPLOSS_HIT (Signal: ";
    private static final String EXIT_PREFIX_TRAILING_STOP = "TRAILING_STOPLOSS_HIT (P&L: ";
    private static final String EXIT_TRAILING_HWM = ", HighWaterMark: ";
    private static final String EXIT_TRAILING_LEVEL = ", TrailLevel: ";
    private static final String EXIT_PREFIX_INDIVIDUAL = "PRICE_DIFF_INDIVIDUAL (Leg: ";
    private static final String EXIT_SUFFIX_POINTS = " points)";
    private static final String EXIT_SUFFIX_DIFF = ", Diff: ";

    // HFT: ThreadLocal StringBuilder for exit reason construction - avoids allocation
    private static final ThreadLocal<StringBuilder> EXIT_REASON_BUILDER =
        ThreadLocal.withInitial(() -> new StringBuilder(64));

    // HFT: Direction constants to avoid enum comparison overhead
    private static final double DIRECTION_LONG = 1.0;
    private static final double DIRECTION_SHORT = -1.0;

    public enum PositionDirection {
        LONG,  // e.g., BUY ATM straddle
        SHORT  // e.g., SELL ATM straddle
    }

    @Getter
    private final String executionId;
    private final Map<String, LegMonitor> legsBySymbol = new ConcurrentHashMap<>();
    private final Map<Long, LegMonitor> legsByInstrumentToken = new ConcurrentHashMap<>();
    @Getter
    private final double stopLossPoints;
    @Getter
    private final double targetPoints;
    @Getter
    private final PositionDirection direction;

    @Setter
    private Consumer<String> exitCallback;
    @Setter
    private BiConsumer<String, String> individualLegExitCallback;

    @Getter
    private volatile boolean active = true;
    @Getter
    private String exitReason;

    // Cached immutable list - only rebuilt when legs change
    private volatile List<LegMonitor> cachedLegs;

    // HFT: Pre-cached legs array for fixed-size straddles (avoids iterator allocation in hot path)
    private volatile LegMonitor[] cachedLegsArray;
    private volatile int cachedLegsCount = 0;

    public PositionMonitor(String executionId, double stopLossPoints, double targetPoints) {
        this(executionId, stopLossPoints, targetPoints, PositionDirection.LONG);
    }

    public PositionMonitor(String executionId,
                           double stopLossPoints,
                           double targetPoints,
                           PositionDirection direction) {
        // Delegate to full constructor with trailing stop disabled for backward compatibility
        this(executionId, stopLossPoints, targetPoints, direction, false, 0.0, 0.0);
    }

    /**
     * Full constructor with trailing stop loss support.
     *
     * @param executionId Unique execution identifier
     * @param stopLossPoints Fixed stop loss in points (used when trailing is disabled or before activation)
     * @param targetPoints Target profit in points
     * @param direction Position direction (LONG for buy strategies, SHORT for sell strategies)
     * @param trailingStopEnabled Enable trailing stop loss feature
     * @param trailingActivationPoints P&L threshold to activate trailing (e.g., 1.0 = activate after 1 point profit)
     * @param trailingDistancePoints Distance trailing stop follows behind high-water mark (e.g., 0.5 points)
     */
    public PositionMonitor(String executionId,
                           double stopLossPoints,
                           double targetPoints,
                           PositionDirection direction,
                           boolean trailingStopEnabled,
                           double trailingActivationPoints,
                           double trailingDistancePoints) {
        this.executionId = executionId;
        this.stopLossPoints = stopLossPoints;
        this.targetPoints = targetPoints;
        this.direction = direction != null ? direction : PositionDirection.LONG;
        this.directionMultiplier = (this.direction == PositionDirection.SHORT) ? -1.0 : 1.0;

        // Use primitives for fast comparison - no BigDecimal overhead
        this.cumulativeTargetPoints = targetPoints > 0 ? targetPoints : 2.0;
        this.cumulativeStopPoints = stopLossPoints > 0 ? stopLossPoints : 2.0;

        // Trailing stop loss configuration
        this.trailingStopEnabled = trailingStopEnabled;
        this.trailingActivationPoints = trailingActivationPoints > 0 ? trailingActivationPoints : 1.0;
        this.trailingDistancePoints = trailingDistancePoints > 0 ? trailingDistancePoints : 0.5;

        if (trailingStopEnabled) {
            log.info("Trailing stop enabled for execution {}: activation={} points, distance={} points",
                    executionId, this.trailingActivationPoints, this.trailingDistancePoints);
        }
    }

    /**
     * Add a leg to monitor
     */
    public void addLeg(String orderId, String symbol, long instrumentToken,
                       double entryPrice, int quantity, String type) {
        LegMonitor leg = new LegMonitor(orderId, symbol, instrumentToken, entryPrice, quantity, type);
        legsBySymbol.put(symbol, leg);
        legsByInstrumentToken.put(instrumentToken, leg);

        // HFT: Rebuild cached array for fast iteration (avoids iterator allocation)
        rebuildCachedLegsArray();

        log.info("Added leg to monitor: {} at entry price: {}", symbol, entryPrice);
    }

    /**
     * HFT: Rebuild the cached legs array when legs are added/removed.
     * This is called infrequently (only on leg changes), so allocation is acceptable here.
     */
    private void rebuildCachedLegsArray() {
        LegMonitor[] newArray = legsBySymbol.values().toArray(new LegMonitor[0]);
        cachedLegsCount = newArray.length;
        cachedLegsArray = newArray;
        cachedLegs = null; // Invalidate list cache too
    }

    /**
     * Update price method with cumulative threshold monitoring.
     * Optimized for HFT: minimal object allocation, primitive arithmetic only.
     *
     * HFT Critical Path - this method is called on every tick from WebSocket.
     * Optimizations applied:
     * - Early exit if not active
     * - Direct array access with indexed loop (no iterator allocation)
     * - Inline cumulative calculation to avoid method call overhead
     * - Primitive arithmetic only (no boxing/unboxing)
     *
     * @param ticks ArrayList of Tick objects from WebSocket
     */
    public void updatePriceWithDifferenceCheck(ArrayList<Tick> ticks) {
        // HFT: Ultra-fast early exit check
        if (!active) {
            return;
        }
        if (ticks == null) {
            return;
        }
        final int tickCount = ticks.size();
        if (tickCount == 0) {
            return;
        }

        // HFT: Fast path - update leg prices from ticks with minimal overhead
        // Using indexed loop to avoid iterator allocation
        for (int i = 0; i < tickCount; i++) {
            final Tick tick = ticks.get(i);
            final LegMonitor leg = legsByInstrumentToken.get(tick.getInstrumentToken());
            if (leg != null) {
                leg.currentPrice = tick.getLastTradedPrice();
            }
        }

        // HFT: Inline cumulative P&L check for minimum latency
        checkAndTriggerCumulativeExitFast();
    }

    /**
     * HFT-optimized cumulative exit check with inlined calculation.
     * Separated from main check method for JIT inlining optimization.
     * Uses pre-cached array to avoid iterator allocation on every tick.
     *
     * Includes trailing stop loss logic:
     * 1. Calculate cumulative P&L
     * 2. Check target hit (highest priority)
     * 3. Update high-water mark and trailing stop level if profitable
     * 4. Check trailing stop hit (if activated)
     * 5. Check fixed stop loss hit (fallback)
     *
     * HFT Optimizations:
     * - Early exit pattern for disabled trailing stop (single boolean check)
     * - Flat conditional structure for better branch prediction
     * - All arithmetic uses primitives (no boxing)
     * - Logging only on state transitions (not every tick)
     */
    private void checkAndTriggerCumulativeExitFast() {
        // HFT: Use pre-cached array to avoid iterator allocation
        final LegMonitor[] legs = cachedLegsArray;
        final int count = cachedLegsCount;

        if (legs == null || count == 0) {
            return;
        }

        // HFT: Inline cumulative calculation with indexed loop
        double cumulative = 0.0;
        for (int i = 0; i < count; i++) {
            cumulative += (legs[i].currentPrice - legs[i].entryPrice) * directionMultiplier;
        }

        // HFT: Lazy debug logging - isDebugEnabled() is a simple boolean check
        // No string formatting happens if debug logging is disabled
        if (log.isDebugEnabled()) {
            log.debug("Cumulative P&L for {}: {} points (target: {}, stop: {})",
                    executionId, cumulative, cumulativeTargetPoints, cumulativeStopPoints);
        }

        // Check target hit (profit) - highest priority, most likely exit in profitable strategies
        if (cumulative >= cumulativeTargetPoints) {
            log.warn("Cumulative target hit for execution {}: cumulative={} points, target={} - Closing ALL legs",
                    executionId, formatDouble(cumulative), formatDouble(cumulativeTargetPoints));
            triggerExitAllLegs(buildExitReasonTarget(cumulative));
            return;
        }

        // ==================== TRAILING STOP LOSS LOGIC (HFT OPTIMIZED) ====================
        // HFT: Single boolean check at start - if disabled, skip entire trailing block
        // This is the FAST PATH when trailing is disabled (default)
        if (trailingStopEnabled) {
            // HFT: Check trailing stop hit FIRST (most common check when activated)
            // This ordering optimizes for the steady-state case where HWM isn't changing
            if (trailingStopActivated) {
                // Check if trailing stop was hit
                if (cumulative <= currentTrailingStopLevel) {
                    log.warn("Trailing stoploss hit for execution {}: P&L={} points, HWM={}, trailLevel={} - Closing ALL legs",
                            executionId, formatDouble(cumulative), formatDouble(highWaterMark),
                            formatDouble(currentTrailingStopLevel));
                    triggerExitAllLegs(buildExitReasonTrailingStop(cumulative, highWaterMark, currentTrailingStopLevel));
                    return;
                }

                // HFT: Update HWM only if we have a new peak (branch predicted as unlikely)
                if (cumulative > highWaterMark) {
                    highWaterMark = cumulative;
                    // HFT: Single arithmetic operation, no branching
                    currentTrailingStopLevel = cumulative - trailingDistancePoints;
                }
            } else {
                // Not yet activated - check for activation condition
                if (cumulative >= trailingActivationPoints) {
                    // HFT: Activation is rare (happens once), OK to have logging here
                    highWaterMark = cumulative;
                    currentTrailingStopLevel = cumulative - trailingDistancePoints;
                    trailingStopActivated = true;
                    log.info("Trailing stop ACTIVATED for execution {}: HWM={} points, trailLevel={} points",
                            executionId, formatDouble(highWaterMark), formatDouble(currentTrailingStopLevel));
                }
            }
        }

        // Check fixed stoploss hit (loss) - fallback when trailing not active or not hit
        if (cumulative <= -cumulativeStopPoints) {
            log.warn("Cumulative stoploss hit for execution {}: cumulative={} points, stopLoss={} - Closing ALL legs",
                    executionId, formatDouble(cumulative), formatDouble(cumulativeStopPoints));
            triggerExitAllLegs(buildExitReasonStoploss(cumulative));
        }
    }

    /**
     * Update prices using a map of instrumentToken -> lastPrice and run threshold checks.
     * Optimized for historical replay path.
     */
    public void updateWithTokenPrices(Map<Long, Double> tokenPrices) {
        if (!active || tokenPrices == null || tokenPrices.isEmpty()) {
            return;
        }

        // Update prices with minimal overhead
        for (Map.Entry<Long, Double> e : tokenPrices.entrySet()) {
            final LegMonitor leg = legsByInstrumentToken.get(e.getKey());
            if (leg != null) {
                final Double price = e.getValue();
                if (price != null) {
                    leg.currentPrice = price;
                }
            }
        }

        checkAndTriggerCumulativeExit();
    }

    /**
     * Compute cumulative directional points across all legs using primitives.
     * Uses pre-cached array for maximum performance on hot path.
     */
    private double computeCumulativeDirectionalPointsFast() {
        // HFT: Use pre-cached array to avoid iterator allocation
        final LegMonitor[] legs = cachedLegsArray;
        final int count = cachedLegsCount;

        if (legs == null || count == 0) {
            return 0.0;
        }

        double cumulative = 0.0;
        for (int i = 0; i < count; i++) {
            double rawDiff = legs[i].currentPrice - legs[i].entryPrice;
            cumulative += rawDiff * directionMultiplier;
        }
        return cumulative;
    }

    /**
     * Check cumulative thresholds and trigger exit if met.
     * Uses fast primitive comparison - no BigDecimal overhead.
     * Includes trailing stop loss logic for historical replay path.
     *
     * HFT Optimizations (same as fast path):
     * - Early exit pattern for disabled trailing stop
     * - Flat conditional structure for better branch prediction
     * - All arithmetic uses primitives (no boxing)
     */
    private void checkAndTriggerCumulativeExit() {
        final double cumulative = computeCumulativeDirectionalPointsFast();

        // Check target hit (highest priority)
        if (cumulative >= cumulativeTargetPoints) {
            log.warn("Cumulative target hit for execution {}: cumulative={} points, target={} - Closing ALL legs",
                    executionId, formatDouble(cumulative), formatDouble(cumulativeTargetPoints));
            triggerExitAllLegs(buildExitReasonTarget(cumulative));
            return;
        }

        // Trailing stop loss logic (HFT optimized - same structure as fast path)
        if (trailingStopEnabled) {
            if (trailingStopActivated) {
                // Check if trailing stop was hit
                if (cumulative <= currentTrailingStopLevel) {
                    log.warn("Trailing stoploss hit for execution {}: P&L={} points, HWM={}, trailLevel={} - Closing ALL legs",
                            executionId, formatDouble(cumulative), formatDouble(highWaterMark),
                            formatDouble(currentTrailingStopLevel));
                    triggerExitAllLegs(buildExitReasonTrailingStop(cumulative, highWaterMark, currentTrailingStopLevel));
                    return;
                }

                // Update HWM only if new peak
                if (cumulative > highWaterMark) {
                    highWaterMark = cumulative;
                    currentTrailingStopLevel = cumulative - trailingDistancePoints;
                }
            } else {
                // Not yet activated - check for activation
                if (cumulative >= trailingActivationPoints) {
                    highWaterMark = cumulative;
                    currentTrailingStopLevel = cumulative - trailingDistancePoints;
                    trailingStopActivated = true;
                    log.info("Trailing stop ACTIVATED for execution {}: HWM={} points, trailLevel={} points",
                            executionId, formatDouble(highWaterMark), formatDouble(currentTrailingStopLevel));
                }
            }
        }

        // Check fixed stoploss hit (negative cumulative >= configured stop)
        if (cumulative <= -cumulativeStopPoints) {
            log.warn("Cumulative stoploss hit for execution {}: cumulative={} points, stopLoss={} - Closing ALL legs",
                    executionId, formatDouble(cumulative), formatDouble(cumulativeStopPoints));
            triggerExitAllLegs(buildExitReasonStoploss(cumulative));
        }
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

        String exitReason = buildExitReasonIndividual(legSymbol, priceDifference);

        log.warn("Triggering individual leg exit for {} in execution {} - Reason: {}",
                 legSymbol, executionId, exitReason);

        // Remove the leg from monitoring
        legsBySymbol.remove(legSymbol);
        legsByInstrumentToken.remove(leg.getInstrumentToken());

        // HFT: Rebuild cached array after leg removal
        rebuildCachedLegsArray();

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

    // ==================== HFT OPTIMIZATION: Fast String Building Methods ====================

    /**
     * HFT: Fast double formatting without String.format overhead.
     * Uses ThreadLocal StringBuilder to avoid allocation on hot path.
     */
    private static String formatDouble(double value) {
        // Simple 2 decimal place formatting without String.format
        long scaled = Math.round(value * 100);
        StringBuilder sb = EXIT_REASON_BUILDER.get();
        sb.setLength(0);
        if (scaled < 0) {
            sb.append('-');
            scaled = -scaled;
        }
        sb.append(scaled / 100);
        sb.append('.');
        long frac = scaled % 100;
        if (frac < 10) sb.append('0');
        sb.append(frac);
        return sb.toString();
    }

    /**
     * HFT: Build target exit reason without String.format.
     */
    private static String buildExitReasonTarget(double cumulative) {
        StringBuilder sb = EXIT_REASON_BUILDER.get();
        sb.setLength(0);
        sb.append(EXIT_PREFIX_TARGET);
        appendDouble(sb, cumulative);
        sb.append(EXIT_SUFFIX_POINTS);
        return sb.toString();
    }

    /**
     * HFT: Build stoploss exit reason without String.format.
     */
    private static String buildExitReasonStoploss(double cumulative) {
        StringBuilder sb = EXIT_REASON_BUILDER.get();
        sb.setLength(0);
        sb.append(EXIT_PREFIX_STOPLOSS);
        appendDouble(sb, cumulative);
        sb.append(EXIT_SUFFIX_POINTS);
        return sb.toString();
    }

    /**
     * HFT: Build trailing stoploss exit reason without String.format.
     * Includes P&L, high-water mark, and trail level for full context.
     */
    private static String buildExitReasonTrailingStop(double cumulative, double hwm, double trailLevel) {
        StringBuilder sb = EXIT_REASON_BUILDER.get();
        sb.setLength(0);
        sb.append(EXIT_PREFIX_TRAILING_STOP);
        appendDouble(sb, cumulative);
        sb.append(EXIT_TRAILING_HWM);
        appendDouble(sb, hwm);
        sb.append(EXIT_TRAILING_LEVEL);
        appendDouble(sb, trailLevel);
        sb.append(EXIT_SUFFIX_POINTS);
        return sb.toString();
    }

    /**
     * HFT: Build individual leg exit reason without String.format.
     */
    private static String buildExitReasonIndividual(String legSymbol, double priceDifference) {
        StringBuilder sb = EXIT_REASON_BUILDER.get();
        sb.setLength(0);
        sb.append(EXIT_PREFIX_INDIVIDUAL);
        sb.append(legSymbol);
        sb.append(EXIT_SUFFIX_DIFF);
        appendDouble(sb, priceDifference);
        sb.append(EXIT_SUFFIX_POINTS);
        return sb.toString();
    }

    /**
     * HFT: Append double with 2 decimal places to StringBuilder.
     */
    private static void appendDouble(StringBuilder sb, double value) {
        long scaled = Math.round(value * 100);
        if (scaled < 0) {
            sb.append('-');
            scaled = -scaled;
        }
        sb.append(scaled / 100);
        sb.append('.');
        long frac = scaled % 100;
        if (frac < 10) sb.append('0');
        sb.append(frac);
    }

    /**
     * Get all legs - returns cached immutable list for performance
     */
    public List<LegMonitor> getLegs() {
        List<LegMonitor> cached = cachedLegs;
        if (cached == null) {
            cached = List.copyOf(legsBySymbol.values());
            cachedLegs = cached;
        }
        return cached;
    }

    /**
     * Get legs map by symbol - for checking if specific legs are still active
     */
    public Map<String, LegMonitor> getLegsBySymbol() {
        return legsBySymbol;
    }

    // ==================== TRAILING STOP LOSS GETTERS ====================

    /**
     * Get the current high-water mark (best P&L achieved).
     * @return high-water mark in points
     */
    public double getHighWaterMark() {
        return highWaterMark;
    }

    /**
     * Get the current trailing stop level.
     * @return trailing stop level in points, or Double.NEGATIVE_INFINITY if not activated
     */
    public double getCurrentTrailingStopLevel() {
        return currentTrailingStopLevel;
    }

    /**
     * Check if trailing stop has been activated.
     * @return true if trailing stop is active
     */
    public boolean isTrailingStopActivated() {
        return trailingStopActivated;
    }

    /**
     * Get trailing stop activation threshold.
     * @return activation threshold in points
     */
    public double getTrailingActivationPoints() {
        return trailingActivationPoints;
    }

    /**
     * Get trailing stop distance.
     * @return trail distance in points
     */
    public double getTrailingDistancePoints() {
        return trailingDistancePoints;
    }

    /**
     * Individual leg monitor - optimized for HFT with primitive doubles.
     * Uses volatile for currentPrice to ensure visibility across threads.
     */
    @Getter
    public static class LegMonitor {
        private final String orderId;
        private final String symbol;
        private final long instrumentToken;
        private final double entryPrice;
        private final int quantity;
        private final String type; // CE or PE

        // Volatile double for thread-safe price updates without synchronization overhead
        volatile double currentPrice;

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
         * Get P&L for this leg using primitive arithmetic.
         * @return raw P&L = (currentPrice - entryPrice) * quantity
         */
        public double getPnl() {
            return (currentPrice - entryPrice) * quantity;
        }
    }
}
