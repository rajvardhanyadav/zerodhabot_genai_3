package com.tradingbot.service.strategy.monitoring;

import com.tradingbot.model.SlTargetMode;
import com.tradingbot.service.strategy.monitoring.exit.*;
import com.zerodhatech.models.Tick;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.SynchronizedLongObjectMap;

/**
 * High-performance position monitor using Strategy pattern for exit logic.
 * <p>
 * This is the refactored version of PositionMonitor that separates exit logic into
 * pluggable strategies. Designed for high-frequency trading (HFT) with minimal
 * object allocation and fast primitive operations.
 *
 * <h2>Architecture</h2>
 * <ul>
 *   <li>Uses Strategy pattern for exit evaluation (see {@link ExitStrategy})</li>
 *   <li>Strategies are evaluated in priority order</li>
 *   <li>Zero allocation on hot path (pre-allocated result objects)</li>
 * </ul>
 *
 * <h2>Exit Strategies (evaluated in priority order)</h2>
 * <ol>
 *   <li>{@link TimeBasedForcedExitStrategy} - Time-based forced exit (priority 0)</li>
 *   <li>{@link PremiumBasedExitStrategy} - Premium decay/expansion (priority 50)</li>
 *   <li>{@link PointsBasedExitStrategy} - Fixed-point target (priority 100)</li>
 *   <li>{@link TrailingStopLossStrategy} - Dynamic trailing stop (priority 300)</li>
 *   <li>{@link PointsBasedExitStrategy} - Fixed-point stop loss (priority 400)</li>
 * </ol>
 *
 * <h2>Thread Safety</h2>
 * <ul>
 *   <li>Safe for concurrent price updates from WebSocket threads</li>
 *   <li>Uses volatile fields for visibility without synchronization overhead</li>
 *   <li>ConcurrentHashMap for thread-safe leg lookups</li>
 * </ul>
 *
 * <h2>HFT Optimizations</h2>
 * <ul>
 *   <li>Primitive doubles for arithmetic (no BigDecimal overhead)</li>
 *   <li>Pre-computed direction multiplier</li>
 *   <li>Cached legs array to avoid iterator allocation</li>
 *   <li>Reusable ExitContext to avoid allocation per tick</li>
 * </ul>
 *
 * @see ExitStrategy
 * @see ExitContext
 * @see PositionMonitor
 */
@Slf4j
public class PositionMonitorV2 {

    // ==================== CONFIGURATION ====================

    @Getter
    private final String executionId;

    @Getter
    private final double stopLossPoints;

    @Getter
    private final double targetPoints;

    @Getter
    private final PositionDirection direction;

    /** Pre-computed direction multiplier: 1.0 for LONG, -1.0 for SHORT */
    private final double directionMultiplier;

    @Getter
    private final SlTargetMode slTargetMode;

    // ==================== EXIT STRATEGY CONFIGURATION ====================

    /** Ordered list of exit strategies (immutable after construction) */
    private final List<ExitStrategy> exitStrategies;

    /** Time-based forced exit strategy (for external access) */
    @Getter
    private final TimeBasedForcedExitStrategy timeBasedStrategy;

    /** Trailing stop strategy (for external access to state) */
    @Getter
    private final TrailingStopLossStrategy trailingStopStrategy;

    // ==================== DYNAMIC THRESHOLDS ====================

    /** Mutable cumulative target points (adjusted on individual leg exits) */
    private volatile double cumulativeTargetPoints;

    /** Mutable cumulative stop points */
    private volatile double cumulativeStopPoints;

    // ==================== PREMIUM-BASED CONFIGURATION ====================

    @Getter
    private final boolean premiumBasedExitEnabled;

    @Getter
    private volatile double entryPremium;

    @Getter
    private volatile double targetPremiumLevel;

    @Getter
    private volatile double stopLossPremiumLevel;

    private final double targetDecayPct;
    private final double stopLossExpansionPct;

    // ==================== LEG MANAGEMENT ====================

    private final Map<String, LegMonitor> legsBySymbol = new ConcurrentHashMap<>();
    private final SynchronizedLongObjectMap<LegMonitor> legsByInstrumentToken =
        new SynchronizedLongObjectMap<>(new LongObjectHashMap<>(4));

    /** HFT: Pre-cached legs array for fast iteration */
    private volatile LegMonitor[] cachedLegsArray;
    private volatile int cachedLegsCount = 0;

    // ==================== STATE ====================

    @Getter
    private volatile boolean active = true;

    @Getter
    private String exitReason;

    @Getter
    private volatile String ownerUserId;

    // ==================== CALLBACKS ====================

    @Setter
    private Consumer<String> exitCallback;

    @Setter
    private BiConsumer<String, String> individualLegExitCallback;

    @Setter
    private LegReplacementCallback legReplacementCallback;

    // ==================== HFT: ThreadLocal for debug logging ====================

    private static final ThreadLocal<StringBuilder> DEBUG_LOG_BUILDER =
        ThreadLocal.withInitial(() -> new StringBuilder(128));

    private static final ThreadLocal<StringBuilder> FORMAT_DOUBLE_BUILDER =
        ThreadLocal.withInitial(() -> new StringBuilder(16));

    // ==================== ENUMS AND INTERFACES ====================

    /**
     * Position direction determines how price movements affect P&amp;L calculation.
     */
    public enum PositionDirection {
        /** Long position - profits when price increases */
        LONG,
        /** Short position - profits when price decreases */
        SHORT
    }

    /**
     * Functional interface for leg replacement callback.
     */
    @FunctionalInterface
    public interface LegReplacementCallback {
        void onLegReplacement(String exitedLegSymbol, String legTypeToAdd,
                              double targetPremium, String lossMakingLegSymbol);
    }

    // ==================== CONSTRUCTORS ====================

    /**
     * Creates a position monitor with specified configuration.
     */
    public PositionMonitorV2(String executionId,
                              double stopLossPoints,
                              double targetPoints,
                              PositionDirection direction,
                              boolean trailingStopEnabled,
                              double trailingActivationPoints,
                              double trailingDistancePoints,
                              boolean forcedExitEnabled,
                              LocalTime forcedExitTime,
                              boolean premiumBasedExitEnabled,
                              double entryPremium,
                              double targetDecayPct,
                              double stopLossExpansionPct,
                              SlTargetMode slTargetMode) {

        this.executionId = executionId;
        this.stopLossPoints = stopLossPoints;
        this.targetPoints = targetPoints;
        this.direction = direction != null ? direction : PositionDirection.LONG;
        this.directionMultiplier = (this.direction == PositionDirection.SHORT) ? -1.0 : 1.0;
        this.slTargetMode = slTargetMode != null ? slTargetMode : SlTargetMode.POINTS;

        // Initialize thresholds
        this.cumulativeTargetPoints = targetPoints > 0 ? targetPoints : 2.0;
        this.cumulativeStopPoints = stopLossPoints > 0 ? stopLossPoints : 2.0;

        // Premium-based configuration
        this.premiumBasedExitEnabled = premiumBasedExitEnabled;
        this.entryPremium = entryPremium;
        this.targetDecayPct = normalizePercentage(targetDecayPct, 5.0);
        this.stopLossExpansionPct = normalizePercentage(stopLossExpansionPct, 10.0);

        if (premiumBasedExitEnabled && entryPremium > 0) {
            this.targetPremiumLevel = entryPremium * (1.0 - this.targetDecayPct);
            this.stopLossPremiumLevel = entryPremium * (1.0 + this.stopLossExpansionPct);
        } else {
            this.targetPremiumLevel = 0.0;
            this.stopLossPremiumLevel = Double.MAX_VALUE;
        }

        // Build exit strategies
        List<ExitStrategy> strategies = new ArrayList<>();
        TrailingStopLossStrategy tempTrailingStrategy = null;
        TimeBasedForcedExitStrategy tempTimeStrategy = null;

        // Time-based forced exit (highest priority)
        if (forcedExitEnabled) {
            tempTimeStrategy = new TimeBasedForcedExitStrategy(forcedExitTime);
            strategies.add(tempTimeStrategy);
            log.info("TimeBasedForcedExitStrategy enabled for {}: cutoff={}", executionId, forcedExitTime);
        }
        this.timeBasedStrategy = tempTimeStrategy;

        // Premium-based or Points-based exit
        if (premiumBasedExitEnabled) {
            strategies.add(new PremiumBasedExitStrategy());
            log.info("PremiumBasedExitStrategy enabled for {}: entryPremium={}, targetDecay={}%, slExpansion={}%",
                    executionId, formatDouble(entryPremium), formatDouble(this.targetDecayPct * 100),
                    formatDouble(this.stopLossExpansionPct * 100));
        } else {
            // Points-based target and stop loss
            strategies.add(PointsBasedExitStrategy.forTarget());
            log.info("PointsBasedExitStrategy (TARGET) enabled for {}: target={} pts", executionId, formatDouble(targetPoints));

            // Trailing stop (optional)
            if (trailingStopEnabled) {
                double actPts = trailingActivationPoints > 0 ? trailingActivationPoints : 1.0;
                double distPts = trailingDistancePoints > 0 ? trailingDistancePoints : 0.5;
                tempTrailingStrategy = new TrailingStopLossStrategy(actPts, distPts);
                strategies.add(tempTrailingStrategy);
                log.info("TrailingStopLossStrategy enabled for {}: activation={} pts, distance={} pts",
                        executionId, formatDouble(actPts), formatDouble(distPts));
            }

            // Fixed stop loss (lowest priority)
            strategies.add(PointsBasedExitStrategy.forStopLoss());
            log.info("PointsBasedExitStrategy (STOPLOSS) enabled for {}: SL={} pts", executionId, formatDouble(stopLossPoints));
        }
        this.trailingStopStrategy = tempTrailingStrategy;

        // Sort strategies by priority
        strategies.sort(Comparator.comparingInt(ExitStrategy::getPriority));
        this.exitStrategies = List.copyOf(strategies);

        log.info("PositionMonitorV2 initialized for {}: {} strategies configured, direction={}, mode={}",
                executionId, exitStrategies.size(), this.direction, this.slTargetMode);
    }

    // ==================== SIMPLIFIED CONSTRUCTORS ====================

    /**
     * Creates a position monitor with default LONG direction and points-based exits.
     */
    public PositionMonitorV2(String executionId, double stopLossPoints, double targetPoints) {
        this(executionId, stopLossPoints, targetPoints, PositionDirection.LONG,
             false, 0, 0, false, null, false, 0, 0, 0, null);
    }

    /**
     * Creates a position monitor with specified direction and points-based exits.
     */
    public PositionMonitorV2(String executionId, double stopLossPoints, double targetPoints,
                              PositionDirection direction) {
        this(executionId, stopLossPoints, targetPoints, direction,
             false, 0, 0, false, null, false, 0, 0, 0, null);
    }

    /**
     * Creates a position monitor with trailing stop loss configuration.
     * <p>
     * This constructor enables trailing stop loss without premium-based or time-based exits.
     */
    public PositionMonitorV2(String executionId, double stopLossPoints, double targetPoints,
                              PositionDirection direction,
                              boolean trailingStopEnabled,
                              double trailingActivationPoints,
                              double trailingDistancePoints) {
        this(executionId, stopLossPoints, targetPoints, direction,
             trailingStopEnabled, trailingActivationPoints, trailingDistancePoints,
             false, null, false, 0, 0, 0, null);
    }

    // ==================== LEG MANAGEMENT ====================

    /**
     * Adds a leg to this position monitor for tracking.
     */
    public void addLeg(String orderId, String symbol, long instrumentToken,
                       double entryPrice, int quantity, String type) {
        LegMonitor leg = new LegMonitor(orderId, symbol, instrumentToken, entryPrice, quantity, type);
        legsBySymbol.put(symbol, leg);
        legsByInstrumentToken.put(instrumentToken, leg);
        rebuildCachedLegsArray();
        log.info("Added leg to monitor: {} at entry price: {}", symbol, entryPrice);
    }

    /**
     * Removes a leg from monitoring.
     */
    public void removeLeg(String symbol) {
        LegMonitor leg = legsBySymbol.remove(symbol);
        if (leg != null) {
            legsByInstrumentToken.remove(leg.getInstrumentToken());
            rebuildCachedLegsArray();
            log.info("Removed leg from monitor: {}", symbol);
        }
    }

    private void rebuildCachedLegsArray() {
        LegMonitor[] newArray = legsBySymbol.values().toArray(new LegMonitor[0]);
        cachedLegsCount = newArray.length;
        cachedLegsArray = newArray;
    }

    // ==================== PREMIUM MANAGEMENT ====================

    /**
     * Sets the combined entry premium for premium-based exit calculations.
     */
    public void setEntryPremium(double combinedEntryPremium) {
        if (!premiumBasedExitEnabled) {
            log.warn("setEntryPremium called but premium-based exit is not enabled for {}", executionId);
            return;
        }
        if (combinedEntryPremium <= 0) {
            log.error("Invalid entry premium {} for {}", combinedEntryPremium, executionId);
            return;
        }

        this.entryPremium = combinedEntryPremium;
        this.targetPremiumLevel = combinedEntryPremium * (1.0 - targetDecayPct);
        this.stopLossPremiumLevel = combinedEntryPremium * (1.0 + stopLossExpansionPct);

        log.info("Entry premium set for {}: premium={}, targetLevel={}, slLevel={}",
                executionId, formatDouble(combinedEntryPremium),
                formatDouble(targetPremiumLevel), formatDouble(stopLossPremiumLevel));
    }

    /**
     * Updates entry premium after leg replacement.
     */
    public void updateEntryPremiumAfterLegReplacement() {
        if (!premiumBasedExitEnabled) return;

        final LegMonitor[] legs = cachedLegsArray;
        final int count = cachedLegsCount;
        if (count == 0) return;

        double newCombinedPremium = 0.0;
        for (int i = 0; i < count; i++) {
            newCombinedPremium += legs[i].getEntryPrice();
        }

        if (newCombinedPremium > 0) {
            setEntryPremium(newCombinedPremium);
        }
    }

    /**
     * Adds a replacement leg and updates the premium thresholds.
     * <p>
     * This is a convenience method that combines addLeg() and updateEntryPremiumAfterLegReplacement()
     * for use after an individual leg exit in premium-based exit mode.
     *
     * @param orderId unique order identifier for exit operations
     * @param symbol trading symbol (e.g., "NIFTY24350CE")
     * @param instrumentToken Zerodha instrument token for WebSocket price updates
     * @param entryPrice entry price for this leg
     * @param quantity number of contracts
     * @param type leg type (typically "CE" for Call or "PE" for Put)
     */
    public void addReplacementLeg(String orderId, String symbol, long instrumentToken,
                                   double entryPrice, int quantity, String type) {
        // Add the new leg
        addLeg(orderId, symbol, instrumentToken, entryPrice, quantity, type);

        // Update entry premium and thresholds
        updateEntryPremiumAfterLegReplacement();

        log.info("Replacement leg added and thresholds adjusted for {}: new leg={} at {}, newEntryPremium={}, newTargetLevel={}, newSlLevel={}",
                executionId, symbol, formatDouble(entryPrice),
                formatDouble(entryPremium), formatDouble(targetPremiumLevel), formatDouble(stopLossPremiumLevel));
    }

    // ==================== PRICE UPDATE & EXIT EVALUATION ====================

    /**
     * Updates leg prices from WebSocket ticks and evaluates exit conditions.
     * <p>
     * This is the <b>hot path</b> - called on every tick from WebSocket thread.
     */
    public void updatePriceWithDifferenceCheck(ArrayList<Tick> ticks) {
        if (!active || ticks == null) return;

        final int tickCount = ticks.size();
        if (tickCount == 0) return;

        // Update leg prices
        for (int i = 0; i < tickCount; i++) {
            final Tick tick = ticks.get(i);
            final LegMonitor leg = legsByInstrumentToken.get(tick.getInstrumentToken());
            if (leg != null) {
                leg.setCurrentPrice(tick.getLastTradedPrice());
            }
        }

        // Evaluate exit conditions using strategy pattern
        evaluateExitConditions();
    }

    /**
     * HFT-optimized exit evaluation using strategy pattern.
     */
    private void evaluateExitConditions() {
        final LegMonitor[] legs = cachedLegsArray;
        final int count = cachedLegsCount;
        if (count == 0) return;

        // Calculate cumulative P&L
        double cumulative = 0.0;
        for (int i = 0; i < count; i++) {
            cumulative += (legs[i].getCurrentPrice() - legs[i].getEntryPrice()) * directionMultiplier;
        }

        // Debug logging
        if (log.isDebugEnabled()) {
            final StringBuilder legPrices = DEBUG_LOG_BUILDER.get();
            legPrices.setLength(0);
            for (int i = 0; i < count; i++) {
                if (i > 0) legPrices.append(", ");
                legPrices.append(legs[i].getSymbol()).append('=').append(legs[i].getCurrentPrice());
            }
            log.debug("Cumulative P&L for {}: {} points | Legs: [{}]",
                    executionId, formatDouble(cumulative), legPrices);
        }

        // Create evaluation context
        ExitContext ctx = new ExitContext(
                executionId,
                directionMultiplier,
                direction,
                cumulativeTargetPoints,
                cumulativeStopPoints,
                entryPremium,
                targetPremiumLevel,
                stopLossPremiumLevel,
                legs,
                count,
                individualLegExitCallback,
                legReplacementCallback
        );
        ctx.setCumulativePnL(cumulative);

        // Evaluate strategies in priority order
        for (ExitStrategy strategy : exitStrategies) {
            if (!strategy.isEnabled(ctx)) continue;

            ExitResult result = strategy.evaluate(ctx);

            if (result.requiresAction()) {
                handleExitResult(result);
                return; // Exit after first action
            }
        }
    }

    /**
     * Handles the exit result from a strategy.
     */
    private void handleExitResult(ExitResult result) {
        switch (result.getExitType()) {
            case EXIT_ALL -> triggerExitAllLegs(result.getExitReason());

            case EXIT_LEG -> {
                if (individualLegExitCallback != null) {
                    individualLegExitCallback.accept(result.getLegSymbol(), result.getExitReason());
                }
                removeLeg(result.getLegSymbol());
            }

            case ADJUST_LEG -> {
                if (individualLegExitCallback != null) {
                    individualLegExitCallback.accept(result.getLegSymbol(), result.getExitReason());
                }
                removeLeg(result.getLegSymbol());

                if (legReplacementCallback != null) {
                    legReplacementCallback.onLegReplacement(
                            result.getLegSymbol(),
                            result.getNewLegType(),
                            result.getTargetPremiumForNewLeg(),
                            result.getLossMakingLegSymbol()
                    );
                }
            }

            case NO_EXIT -> { /* No action needed */ }
        }
    }

    private void triggerExitAllLegs(String reason) {
        if (!active) return;

        active = false;
        exitReason = reason;

        log.warn("Triggering exit for {} - Reason: {}", executionId, exitReason);

        if (exitCallback != null) {
            exitCallback.accept(exitReason);
        }
    }

    // ==================== PUBLIC API ====================

    /**
     * Stops monitoring and prevents further exit evaluations.
     */
    public void stop() {
        active = false;
        log.info("Stopped monitoring for execution: {}", executionId);
    }

    /**
     * Sets the owner user ID for context propagation.
     */
    public void setOwnerUserId(String ownerUserId) {
        if (ownerUserId != null && !ownerUserId.isBlank()) {
            this.ownerUserId = ownerUserId;
            log.debug("PositionMonitorV2 {} owner set to userId={}", executionId, ownerUserId);
        }
    }

    /**
     * Get all legs.
     */
    public List<LegMonitor> getLegs() {
        return List.copyOf(legsBySymbol.values());
    }

    /**
     * Get legs map by symbol.
     */
    public Map<String, LegMonitor> getLegsBySymbol() {
        return legsBySymbol;
    }

    /**
     * Get configured exit strategies.
     */
    public List<ExitStrategy> getExitStrategies() {
        return exitStrategies;
    }

    /**
     * Get current cumulative target points.
     */
    public double getCumulativeTargetPoints() {
        return cumulativeTargetPoints;
    }

    /**
     * Get current cumulative stop points.
     */
    public double getCumulativeStopPoints() {
        return cumulativeStopPoints;
    }

    /**
     * Check if trailing stop is enabled.
     */
    public boolean isTrailingStopEnabled() {
        return trailingStopStrategy != null;
    }

    /**
     * Check if forced exit is enabled.
     */
    public boolean isForcedExitEnabled() {
        return timeBasedStrategy != null;
    }

    /**
     * Get forced exit time.
     */
    public LocalTime getForcedExitTime() {
        return timeBasedStrategy != null ? timeBasedStrategy.getForcedExitTime() : null;
    }

    /**
     * Manually trigger forced exit.
     */
    public boolean triggerForcedExit() {
        if (!active) return false;
        if (timeBasedStrategy != null && timeBasedStrategy.triggerManually()) {
            triggerExitAllLegs("MANUAL_FORCED_EXIT");
            return true;
        }
        return false;
    }

    // ==================== UTILITY METHODS ====================

    private static double normalizePercentage(double value, double defaultPct) {
        if (value <= 0) return defaultPct / 100.0;
        if (value > 1.0) return value / 100.0;
        return value;
    }

    private static String formatDouble(double value) {
        long scaled = Math.round(value * 100);
        StringBuilder sb = FORMAT_DOUBLE_BUILDER.get();
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
}

