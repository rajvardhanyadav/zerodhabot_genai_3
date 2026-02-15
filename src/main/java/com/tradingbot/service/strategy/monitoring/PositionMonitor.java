package com.tradingbot.service.strategy.monitoring;

import com.tradingbot.model.SlTargetMode;
import com.zerodhatech.models.Tick;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.SynchronizedLongObjectMap;

/**
 * High-performance position monitor for tracking and managing multi-leg options strategies.
 * <p>
 * This monitor tracks cumulative P&amp;L across all legs and triggers exits based on configurable
 * thresholds. Designed for high-frequency trading (HFT) with minimal object allocation and
 * fast primitive operations.
 *
 * <h2>Exit Logic Priority (evaluated in order)</h2>
 * <ol>
 *   <li><b>Cumulative Target Hit</b> - Exits all legs when total profit reaches target</li>
 *   <li><b>Individual Leg Stop Loss</b> - Exits specific leg when it loses threshold points (SHORT strategies only)</li>
 *   <li><b>Trailing Stop Loss</b> - Exits all legs when P&amp;L falls below dynamic trailing level</li>
 *   <li><b>Fixed Cumulative Stop Loss</b> - Exits all legs when total loss reaches stop loss threshold</li>
 * </ol>
 *
 * <h2>Individual Leg Exit (SHORT strategies only)</h2>
 * <p>
 * When a leg hits its individual stop loss (e.g., -3 points):
 * <ul>
 *   <li>Only that leg is exited</li>
 *   <li>Target is adjusted for remaining legs: newTarget = originalTarget + stopLossPoints</li>
 *   <li>Remaining legs continue monitoring with the adjusted target</li>
 * </ul>
 *
 * <h2>Position Direction</h2>
 * <ul>
 *   <li><b>LONG</b> - Buy strategies (e.g., BUY ATM straddle): profit when price increases</li>
 *   <li><b>SHORT</b> - Sell strategies (e.g., SELL ATM straddle): profit when price decreases</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <ul>
 *   <li>Safe for concurrent price updates from WebSocket threads</li>
 *   <li>Uses volatile fields for visibility without synchronization overhead</li>
 *   <li>ConcurrentHashMap for thread-safe leg lookups</li>
 *   <li>Callbacks executed on the WebSocket thread - keep them fast</li>
 * </ul>
 *
 * <h2>HFT Optimizations</h2>
 * <ul>
 *   <li>Primitive doubles for arithmetic (no BigDecimal overhead)</li>
 *   <li>Pre-computed direction multiplier for single multiplication</li>
 *   <li>Cached legs array to avoid iterator allocation on hot path</li>
 *   <li>ThreadLocal StringBuilder for zero-allocation string building</li>
 *   <li>Lazy debug logging to avoid formatting when disabled</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * PositionMonitor monitor = new PositionMonitor("exec123", 2.0, 2.0, PositionDirection.SHORT);
 * monitor.setExitCallback(reason -> closeAllPositions(reason));
 * monitor.setIndividualLegExitCallback((orderId, reason) -> closePosition(orderId, reason));
 * monitor.addLeg("order1", "NIFTY24350CE", 123456L, 100.0, 50, "CE");
 * monitor.addLeg("order2", "NIFTY24350PE", 123457L, 95.0, 50, "PE");
 * // Price updates come from WebSocket
 * monitor.updatePriceWithDifferenceCheck(ticks);
 * }</pre>
 *
 * @see LegMonitor
 * @see PositionDirection
 */
@Slf4j
public class PositionMonitor {

    // Use primitive doubles for fast threshold checks - no BigDecimal overhead
    // cumulativeTargetPoints is mutable to allow dynamic adjustment when individual legs are exited
    private volatile double cumulativeTargetPoints;
    private volatile double cumulativeStopPoints;

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

    // ==================== TIME-BASED FORCED EXIT CONFIGURATION ====================
    // IST timezone for market hours comparison
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Forced exit cutoff time (IST). Positions will be force-exited at or after this time.
     * Default: 15:10 (3:10 PM IST) to ensure exit before market close.
     */
    @Getter
    private final LocalTime forcedExitTime;

    /**
     * Enable/disable time-based forced exit feature.
     * When false, positions are only exited via target/stop-loss conditions.
     */
    @Getter
    private final boolean forcedExitEnabled;

    /**
     * Flag indicating if forced exit has already been triggered.
     * Ensures idempotency - prevents duplicate exit attempts.
     */
    private volatile boolean forcedExitTriggered = false;

    // HFT: Pre-built exit reason for time-based forced exit
    private static final String EXIT_PREFIX_FORCED_TIME = "TIME_BASED_FORCED_EXIT @ ";

    // ==================== PREMIUM-BASED EXIT CONFIGURATION ====================

    /**
     * Enable dynamic premium-based exit mode.
     * When true, uses percentage-based decay/expansion for exits.
     * When false (default), uses traditional fixed-point MTM thresholds.
     */
    @Getter
    private final boolean premiumBasedExitEnabled;

    /**
     * Combined entry premium (CE + PE) captured at straddle placement.
     * Used as reference for calculating decay/expansion percentages.
     * Only relevant when premiumBasedExitEnabled is true.
     */
    @Getter
    private volatile double entryPremium;

    /**
     * Target decay percentage for premium exit (profit).
     * Exit when: combinedLTP <= entryPremium * (1 - targetDecayPct)
     * Example: 0.05 = exit when premium drops 5%
     */
    private final double targetDecayPct;

    /**
     * Stop loss expansion percentage for premium exit (loss).
     * Exit when: combinedLTP >= entryPremium * (1 + stopLossExpansionPct)
     * Example: 0.10 = exit when premium rises 10%
     */
    private final double stopLossExpansionPct;

    /**
     * Pre-computed target premium level (for HFT optimization).
     * Calculated as: entryPremium * (1 - targetDecayPct)
     */
    @Getter
    private volatile double targetPremiumLevel;

    /**
     * Pre-computed stop loss premium level (for HFT optimization).
     * Calculated as: entryPremium * (1 + stopLossExpansionPct)
     */
    @Getter
    private volatile double stopLossPremiumLevel;

    // ==================== SL/TARGET MODE CONFIGURATION ====================

    /**
     * Stop-loss and target calculation mode.
     * <ul>
     *   <li>POINTS: Fixed point-based exits (stopLossPoints/targetPoints)</li>
     *   <li>PREMIUM: Percentage-based on combined entry premium (targetDecayPct/stopLossExpansionPct)</li>
     *   <li>MTM: Mark-to-market P&L based exits</li>
     * </ul>
     * When null, defaults to POINTS (or PREMIUM if premiumBasedExitEnabled is true).
     */
    @Getter
    private final SlTargetMode slTargetMode;

    // HFT: Pre-built exit reason prefixes for premium-based exits
    private static final String EXIT_PREFIX_PREMIUM_DECAY = "PREMIUM_DECAY_TARGET_HIT (Combined LTP: ";
    private static final String EXIT_PREFIX_PREMIUM_EXPANSION = "PREMIUM_EXPANSION_SL_HIT (Combined LTP: ";
    private static final String EXIT_PREFIX_PREMIUM_LEG_ADJUSTMENT = "PREMIUM_LEG_ADJUSTMENT (Profitable leg: ";
    private static final String EXIT_SUFFIX_ENTRY = ", Entry: ";
    private static final String EXIT_SUFFIX_TARGET_LEVEL = ", TargetLevel: ";
    private static final String EXIT_SUFFIX_SL_LEVEL = ", SL Level: ";
    private static final String EXIT_SUFFIX_HALF_THRESHOLD = ", HalfThreshold: ";
    private static final String EXIT_SUFFIX_COMBINED_LTP = ", CombinedLTP: ";
    private static final String EXIT_SUFFIX_CLOSE = ")";

    // HFT: Pre-built exit reason prefixes to avoid String.format on hot path
    // Using StringBuilder with pre-computed components is faster than String.format
    private static final String EXIT_PREFIX_TARGET = "CUMULATIVE_TARGET_HIT (Signal: ";
    private static final String EXIT_PREFIX_STOPLOSS = "CUMULATIVE_STOPLOSS_HIT (Signal: ";
    private static final String EXIT_PREFIX_TRAILING_STOP = "TRAILING_STOPLOSS_HIT (P&L: ";
    private static final String EXIT_PREFIX_INDIVIDUAL_LEG_STOP = "INDIVIDUAL_LEG_STOP (Symbol: ";
    private static final String EXIT_TRAILING_HWM = ", HighWaterMark: ";
    private static final String EXIT_TRAILING_LEVEL = ", TrailLevel: ";
    private static final String EXIT_SUFFIX_POINTS = " points)";
    private static final String EXIT_SUFFIX_PNL = ", P&L: ";

    // HFT: ThreadLocal StringBuilder for exit reason construction - avoids allocation
    private static final ThreadLocal<StringBuilder> EXIT_REASON_BUILDER =
        ThreadLocal.withInitial(() -> new StringBuilder(64));

    // HFT: Separate ThreadLocal StringBuilder for debug logging to avoid conflict with exit reason builder
    private static final ThreadLocal<StringBuilder> DEBUG_LOG_BUILDER =
        ThreadLocal.withInitial(() -> new StringBuilder(128));

    // HFT: Dedicated ThreadLocal for formatDouble() to avoid conflict when called in log statements
    private static final ThreadLocal<StringBuilder> FORMAT_DOUBLE_BUILDER =
        ThreadLocal.withInitial(() -> new StringBuilder(16));


    /**
     * Position direction determines how price movements affect P&amp;L calculation.
     * <p>
     * The direction multiplier is applied to price differences:
     * <ul>
     *   <li><b>LONG (multiplier = 1.0)</b>: Profit when price increases (e.g., BUY strategies)</li>
     *   <li><b>SHORT (multiplier = -1.0)</b>: Profit when price decreases (e.g., SELL strategies)</li>
     * </ul>
     * <p>
     * Example for SHORT: If entry=100 and current=105, rawDiff=+5, but P&amp;L=-5 (loss)
     */
    public enum PositionDirection {
        /** Long position - profits when price increases */
        LONG,
        /** Short position - profits when price decreases */
        SHORT
    }

    @Getter
    private final String executionId;
    private final Map<String, LegMonitor> legsBySymbol = new ConcurrentHashMap<>();
    // HFT: Use primitive long-keyed map to avoid Long autoboxing on every tick lookup
    private final SynchronizedLongObjectMap<LegMonitor> legsByInstrumentToken =
        new SynchronizedLongObjectMap<>(new LongObjectHashMap<>(4));
    @Getter
    private final double stopLossPoints;
    @Getter
    private final double targetPoints;
    @Getter
    private final PositionDirection direction;

    /**
     * CLOUD RUN COMPATIBILITY: Owner user ID for context propagation.
     * <p>
     * In Cloud Run, WebSocket callbacks and executor threads don't inherit ThreadLocal context.
     * This field stores the userId of the user who created the strategy, allowing exit callbacks
     * to restore the correct user context before placing exit orders.
     * <p>
     * Set via constructor or {@link #setOwnerUserId(String)}.
     */
    @Getter
    private volatile String ownerUserId;

    @Setter
    private Consumer<String> exitCallback;

    @Setter
    private BiConsumer<String, String> individualLegExitCallback;

    /**
     * Callback for leg replacement in premium-based exit mode.
     * <p>
     * When a profitable leg is exited due to premium-based individual leg exit,
     * this callback is invoked to add a new leg with similar premium to the loss-making leg.
     * <p>
     * Parameters:
     * <ul>
     *   <li>String: Symbol of the exited leg</li>
     *   <li>String: Type of leg to add (CE or PE - opposite of the exited leg)</li>
     *   <li>Double: Target premium for the new leg (similar to loss-making leg's current price)</li>
     * </ul>
     * Returns the new leg details (orderId, symbol, instrumentToken, entryPrice) via callback completion.
     */
    @Setter
    private LegReplacementCallback legReplacementCallback;

    /**
     * Functional interface for leg replacement callback.
     * Used in premium-based individual leg exit to add a replacement leg.
     */
    @FunctionalInterface
    public interface LegReplacementCallback {
        /**
         * Called when a new leg needs to be added to replace an exited profitable leg.
         *
         * @param exitedLegSymbol symbol of the leg that was exited
         * @param legTypeToAdd type of new leg to add (CE or PE)
         * @param targetPremium target premium for the new leg (similar to loss-making leg)
         * @param lossMakingLegSymbol symbol of the loss-making leg (for reference)
         */
        void onLegReplacement(String exitedLegSymbol, String legTypeToAdd, double targetPremium, String lossMakingLegSymbol);
    }

    /**
     * Sets the owner user ID for context propagation in Cloud Run.
     * <p>
     * This should be called immediately after constructing the monitor,
     * before any callbacks are triggered.
     *
     * @param ownerUserId the user ID who owns this strategy execution
     */
    public void setOwnerUserId(String ownerUserId) {
        if (ownerUserId != null && !ownerUserId.isBlank()) {
            this.ownerUserId = ownerUserId;
            log.debug("PositionMonitor {} owner set to userId={}", executionId, ownerUserId);
        }
    }

    @Getter
    private volatile boolean active = true;
    @Getter
    private String exitReason;

    // Cached immutable list - only rebuilt when legs change
    private volatile List<LegMonitor> cachedLegs;

    // HFT: Pre-cached legs array for fixed-size straddles (avoids iterator allocation in hot path)
    private volatile LegMonitor[] cachedLegsArray;
    private volatile int cachedLegsCount = 0;

    /**
     * Creates a position monitor with default LONG direction.
     * <p>
     * This constructor is for backward compatibility. Defaults to LONG direction
     * and disables trailing stop loss and forced exit.
     *
     * @param executionId unique execution identifier
     * @param stopLossPoints stop loss threshold in points (e.g., 2.0 = exit when loss reaches 2 points)
     * @param targetPoints target profit threshold in points (e.g., 2.0 = exit when profit reaches 2 points)
     */
    public PositionMonitor(String executionId, double stopLossPoints, double targetPoints) {
        this(executionId, stopLossPoints, targetPoints, PositionDirection.LONG);
    }

    /**
     * Creates a position monitor with specified direction.
     * <p>
     * Disables trailing stop loss and forced exit by default. Use the full constructor if these features are needed.
     *
     * @param executionId unique execution identifier
     * @param stopLossPoints stop loss threshold in points
     * @param targetPoints target profit threshold in points
     * @param direction position direction (LONG for buy strategies, SHORT for sell strategies)
     */
    public PositionMonitor(String executionId,
                           double stopLossPoints,
                           double targetPoints,
                           PositionDirection direction) {
        // Delegate to full constructor with all optional features disabled for backward compatibility
        this(executionId, stopLossPoints, targetPoints, direction, false, 0.0, 0.0, false, null,
             false, 0.0, 0.0, 0.0, null);
    }

    /**
     * Creates a position monitor with full configuration including trailing stop loss.
     * <p>
     * This constructor is for backward compatibility. Defaults to forced exit and premium-based exit disabled.
     * Use the complete constructor if these features are needed.
     *
     * @param executionId unique execution identifier
     * @param stopLossPoints fixed stop loss in points (used when trailing is disabled or before activation)
     * @param targetPoints target profit in points (dynamically adjusted on individual leg exits)
     * @param direction position direction (LONG for buy strategies, SHORT for sell strategies)
     * @param trailingStopEnabled enable trailing stop loss feature
     * @param trailingActivationPoints P&amp;L threshold to activate trailing (e.g., 1.0 = activate after 1 point profit)
     * @param trailingDistancePoints distance trailing stop follows behind high-water mark (e.g., 0.5 points)
     */
    public PositionMonitor(String executionId,
                           double stopLossPoints,
                           double targetPoints,
                           PositionDirection direction,
                           boolean trailingStopEnabled,
                           double trailingActivationPoints,
                           double trailingDistancePoints) {
        this(executionId, stopLossPoints, targetPoints, direction, trailingStopEnabled,
             trailingActivationPoints, trailingDistancePoints, false, null,
             false, 0.0, 0.0, 0.0, null);
    }

    /**
     * Creates a position monitor with complete configuration including trailing stop loss and forced exit.
     * <p>
     * This constructor is for backward compatibility. Defaults to premium-based exit disabled.
     * Use the complete constructor with premium parameters for premium-based exits.
     *
     * @param executionId unique execution identifier
     * @param stopLossPoints fixed stop loss in points (used when trailing is disabled or before activation)
     * @param targetPoints target profit in points (dynamically adjusted on individual leg exits)
     * @param direction position direction (LONG for buy strategies, SHORT for sell strategies)
     * @param trailingStopEnabled enable trailing stop loss feature
     * @param trailingActivationPoints P&amp;L threshold to activate trailing (e.g., 1.0 = activate after 1 point profit)
     * @param trailingDistancePoints distance trailing stop follows behind high-water mark (e.g., 0.5 points)
     * @param forcedExitEnabled enable time-based forced exit feature
     * @param forcedExitTime cutoff time for forced exit in IST (null defaults to 15:10)
     */
    public PositionMonitor(String executionId,
                           double stopLossPoints,
                           double targetPoints,
                           PositionDirection direction,
                           boolean trailingStopEnabled,
                           double trailingActivationPoints,
                           double trailingDistancePoints,
                           boolean forcedExitEnabled,
                           LocalTime forcedExitTime) {
        this(executionId, stopLossPoints, targetPoints, direction, trailingStopEnabled,
             trailingActivationPoints, trailingDistancePoints, forcedExitEnabled, forcedExitTime,
             false, 0.0, 0.0, 0.0, null);
    }

    /**
     * Creates a position monitor with FULL configuration including all exit mechanisms.
     * <p>
     * This is the master constructor that supports:
     * <ul>
     *   <li>Fixed-point MTM exits (default mode)</li>
     *   <li>Premium-based percentage exits (when premiumBasedExitEnabled=true)</li>
     *   <li>Trailing stop loss</li>
     *   <li>Time-based forced exit</li>
     * </ul>
     * <p>
     * When premiumBasedExitEnabled=true:
     * <ul>
     *   <li>Target: exit when combinedLTP <= entryPremium * (1 - targetDecayPct)</li>
     *   <li>Stop Loss: exit when combinedLTP >= entryPremium * (1 + stopLossExpansionPct)</li>
     * </ul>
     *
     * @param executionId unique execution identifier
     * @param stopLossPoints fixed stop loss in points (fallback when premium exit disabled)
     * @param targetPoints target profit in points (fallback when premium exit disabled)
     * @param direction position direction (LONG for buy strategies, SHORT for sell strategies)
     * @param trailingStopEnabled enable trailing stop loss feature
     * @param trailingActivationPoints P&amp;L threshold to activate trailing
     * @param trailingDistancePoints distance trailing stop follows behind high-water mark
     * @param forcedExitEnabled enable time-based forced exit feature
     * @param forcedExitTime cutoff time for forced exit in IST (null defaults to 15:10)
     * @param premiumBasedExitEnabled enable premium-based percentage exits
     * @param entryPremium combined entry premium (CE + PE) for reference
     * @param targetDecayPct target decay percentage (e.g., 0.05 = 5%)
     * @param stopLossExpansionPct stop loss expansion percentage (e.g., 0.10 = 10%)
     * @param slTargetMode SL/Target calculation mode (POINTS, PREMIUM, MTM)
     */
    public PositionMonitor(String executionId,
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

        // Use primitives for fast comparison - no BigDecimal overhead
        this.cumulativeTargetPoints = targetPoints > 0 ? targetPoints : 2.0;
        this.cumulativeStopPoints = stopLossPoints > 0 ? stopLossPoints : 2.0;

        // Trailing stop loss configuration
        this.trailingStopEnabled = trailingStopEnabled;
        this.trailingActivationPoints = trailingActivationPoints > 0 ? trailingActivationPoints : 1.0;
        this.trailingDistancePoints = trailingDistancePoints > 0 ? trailingDistancePoints : 0.5;

        // Forced exit time configuration
        this.forcedExitEnabled = forcedExitEnabled;
        this.forcedExitTime = forcedExitTime != null ? forcedExitTime : LocalTime.of(15, 10);

        // SL/Target mode configuration
        this.slTargetMode = slTargetMode != null ? slTargetMode : SlTargetMode.POINTS;

        // Premium-based exit configuration
        this.premiumBasedExitEnabled = premiumBasedExitEnabled;
        this.entryPremium = entryPremium;

        // Convert percentage values (1-100) to decimal fractions (0.01-1.0)
        // If value > 1, treat as percentage and divide by 100; otherwise use as-is
        // Default: 5% for target decay, 10% for stop loss expansion
        double normalizedTargetDecay = normalizePercentage(targetDecayPct, 5.0);
        double normalizedStopLossExpansion = normalizePercentage(stopLossExpansionPct, 10.0);

        this.targetDecayPct = normalizedTargetDecay;
        this.stopLossExpansionPct = normalizedStopLossExpansion;

        // Pre-compute premium threshold levels for HFT optimization
        // Target: exit when premium decays BY targetDecayPct (e.g., 5% decay = 95% of entry)
        // StopLoss: exit when premium expands BY stopLossExpansionPct (e.g., 10% expansion = 110% of entry)
        if (premiumBasedExitEnabled && entryPremium > 0) {
            this.targetPremiumLevel = entryPremium * (1.0 - this.targetDecayPct);
            this.stopLossPremiumLevel = entryPremium * (1.0 + this.stopLossExpansionPct);
        } else {
            this.targetPremiumLevel = 0.0;
            this.stopLossPremiumLevel = Double.MAX_VALUE;
        }

        if (trailingStopEnabled) {
            log.info("Trailing stop enabled for execution {}: activation={} points, distance={} points",
                    executionId, this.trailingActivationPoints, this.trailingDistancePoints);
        }

        if (forcedExitEnabled) {
            log.info("Forced exit enabled for execution {}: cutoff time={} IST",
                    executionId, this.forcedExitTime);
        }

        if (premiumBasedExitEnabled) {
            log.info("Premium-based exit enabled for execution {}: entryPremium={}, targetDecay={}%, stopLossExpansion={}%, targetLevel={}, slLevel={}",
                    executionId, formatDouble(entryPremium), formatDouble(this.targetDecayPct * 100),
                    formatDouble(this.stopLossExpansionPct * 100), formatDouble(this.targetPremiumLevel),
                    formatDouble(this.stopLossPremiumLevel));
        }

        log.info("Position monitor initialized for execution {}: slTargetMode={}, direction={}, target={} pts, SL={} pts",
                executionId, this.slTargetMode, this.direction, formatDouble(targetPoints), formatDouble(stopLossPoints));
    }

    /**
     * Adds a leg to this position monitor for tracking.
     * <p>
     * Each leg represents a single option contract (CE or PE) in a multi-leg strategy.
     * Legs are indexed by both symbol and instrument token for fast lookups during
     * price updates.
     *
     * @param orderId unique order identifier for exit operations
     * @param symbol trading symbol (e.g., "NIFTY24350CE")
     * @param instrumentToken Zerodha instrument token for WebSocket price updates
     * @param entryPrice entry price for this leg
     * @param quantity number of contracts
     * @param type leg type (typically "CE" for Call or "PE" for Put)
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
     * Sets the combined entry premium for premium-based exit calculations.
     * <p>
     * This method should be called after all legs are added to capture the total premium.
     * Pre-computes target and stop loss levels for HFT optimization.
     * <p>
     * Thread-safe: Can be called after monitor creation when actual fill prices are known.
     *
     * @param combinedEntryPremium sum of all leg entry prices (CE price + PE price)
     */
    public void setEntryPremium(double combinedEntryPremium) {
        if (!premiumBasedExitEnabled) {
            log.warn("setEntryPremium called but premium-based exit is not enabled for execution {}", executionId);
            return;
        }
        if (combinedEntryPremium <= 0) {
            log.error("Invalid entry premium {} for execution {}. Premium must be positive.", combinedEntryPremium, executionId);
            return;
        }

        this.entryPremium = combinedEntryPremium;
        // Re-compute threshold levels with actual premium
        this.targetPremiumLevel = combinedEntryPremium * (1.0 - targetDecayPct);
        this.stopLossPremiumLevel = combinedEntryPremium * (1.0 + stopLossExpansionPct);

        log.info("Entry premium set for execution {}: premium={}, targetLevel={} ({}% decay), slLevel={} ({}% expansion)",
                executionId, formatDouble(combinedEntryPremium), formatDouble(targetPremiumLevel),
                formatDouble(targetDecayPct * 100), formatDouble(stopLossPremiumLevel),
                formatDouble(stopLossExpansionPct * 100));
    }

    /**
     * Updates the entry premium and recalculates thresholds after a leg replacement.
     * <p>
     * This method should be called after adding a replacement leg following an individual
     * leg exit in premium-based exit mode. It recalculates the combined entry premium
     * based on all current legs and adjusts target/stop-loss levels accordingly.
     * <p>
     * The new thresholds are calculated using the same percentages but with the new
     * combined premium as the reference point.
     * <p>
     * Thread-safe: Can be called after leg replacement when new leg fill is confirmed.
     */
    public void updateEntryPremiumAfterLegReplacement() {
        if (!premiumBasedExitEnabled) {
            log.warn("updateEntryPremiumAfterLegReplacement called but premium-based exit is not enabled for execution {}", executionId);
            return;
        }

        // Calculate new combined entry premium from all current legs' entry prices
        final LegMonitor[] legs = cachedLegsArray;
        final int count = cachedLegsCount;
        if (count == 0) {
            log.warn("No legs found for execution {} when updating entry premium after replacement", executionId);
            return;
        }

        double newCombinedPremium = 0.0;
        for (int i = 0; i < count; i++) {
            newCombinedPremium += legs[i].entryPrice;
        }

        if (newCombinedPremium <= 0) {
            log.error("Invalid combined premium {} for execution {} after leg replacement", newCombinedPremium, executionId);
            return;
        }

        final double oldEntryPremium = this.entryPremium;
        final double oldTargetLevel = this.targetPremiumLevel;
        final double oldSlLevel = this.stopLossPremiumLevel;

        this.entryPremium = newCombinedPremium;
        // Re-compute threshold levels with new combined premium
        this.targetPremiumLevel = newCombinedPremium * (1.0 - targetDecayPct);
        this.stopLossPremiumLevel = newCombinedPremium * (1.0 + stopLossExpansionPct);

        log.info("Entry premium updated after leg replacement for execution {}: " +
                        "oldPremium={} -> newPremium={}, " +
                        "oldTargetLevel={} -> newTargetLevel={} ({}% decay), " +
                        "oldSlLevel={} -> newSlLevel={} ({}% expansion)",
                executionId,
                formatDouble(oldEntryPremium), formatDouble(newCombinedPremium),
                formatDouble(oldTargetLevel), formatDouble(targetPremiumLevel), formatDouble(targetDecayPct * 100),
                formatDouble(oldSlLevel), formatDouble(stopLossPremiumLevel), formatDouble(stopLossExpansionPct * 100));
    }

    /**
     * Adds a replacement leg and updates the premium thresholds.
     * <p>
     * This is a convenience method that combines addLeg() and updateEntryPremiumAfterLegReplacement()
     * for use after an individual leg exit in premium-based exit mode.
     * <p>
     * After calling this method, the monitor will continue tracking with the new combined
     * premium and adjusted target/stop-loss levels.
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

        log.info("Replacement leg added and thresholds adjusted for execution {}: " +
                        "new leg={} at {}, newEntryPremium={}, newTargetLevel={}, newSlLevel={}",
                executionId, symbol, formatDouble(entryPrice),
                formatDouble(entryPremium), formatDouble(targetPremiumLevel), formatDouble(stopLossPremiumLevel));
    }

    /**
     * Rebuilds the cached legs array when legs are added or removed.
     * <p>
     * This is called infrequently (only on leg changes), so allocation overhead is acceptable.
     * The cached array enables fast indexed iteration in the hot path (price update processing)
     * without iterator allocation.
     */
    private void rebuildCachedLegsArray() {
        LegMonitor[] newArray = legsBySymbol.values().toArray(new LegMonitor[0]);
        cachedLegsCount = newArray.length;
        cachedLegsArray = newArray;
        cachedLegs = null; // Invalidate list cache too
    }

    /**
     * Updates leg prices from WebSocket ticks and evaluates exit conditions.
     * <p>
     * This is the <b>hot path</b> - called on every tick from WebSocket thread.
     * Optimized for minimal latency:
     * <ul>
     *   <li>Early exit if monitor is not active</li>
     *   <li>Direct array access with indexed loop (no iterator allocation)</li>
     *   <li>Inline cumulative calculation to avoid method call overhead</li>
     *   <li>Primitive arithmetic only (no boxing/unboxing)</li>
     * </ul>
     *
     * @param ticks list of tick updates from WebSocket (may be null or empty)
     */
    public void updatePriceWithDifferenceCheck(ArrayList<Tick> ticks) {
        // HFT: Single volatile read first, then null check
        if (!active || ticks == null) {
            return;
        }

        // HFT: Get size once and use for both empty check and loop bound
        final int tickCount = ticks.size();
        if (tickCount == 0) {
            return;
        }

        // HFT: Fast path - update leg prices from ticks with minimal overhead
        // Using indexed loop to avoid iterator allocation
        // SynchronizedLongObjectMap.get(long) avoids Long autoboxing
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
     * HFT-optimized exit evaluation with inlined P&amp;L calculation and individual leg exit support.
     * <p>
     * Evaluates exit conditions in priority order:
     * <ol>
     *   <li><b>Cumulative target</b> - Full exit when total profit >= target</li>
     *   <li><b>Individual leg stop loss</b> - Leg exit when individual leg P&amp;L <= -stopLossPoints (SHORT only)</li>
     *   <li><b>Trailing stop loss</b> - Full exit when P&amp;L <= trailing stop level (if activated)</li>
     *   <li><b>Fixed cumulative stop loss</b> - Full exit when total loss <= -stopLossPoints</li>
     * </ol>
     * <p>
     * <b>Individual Leg Exit Logic (SHORT strategies only):</b>
     * <ul>
     *   <li>Monitors each leg's P&amp;L independently</li>
     *   <li>Exits leg if P&amp;L <= -stopLossPoints (e.g., -3 points)</li>
     *   <li>Adjusts target for remaining legs: newTarget = currentTarget + stopLossPoints</li>
     *   <li>Continues monitoring remaining legs</li>
     * </ul>
     * <p>
     * <b>HFT Optimizations:</b>
     * <ul>
     *   <li>Uses pre-cached array to avoid iterator allocation</li>
     *   <li>Inline cumulative calculation with indexed loop</li>
     *   <li>Flat conditional structure for better branch prediction</li>
     *   <li>All arithmetic uses primitives (no boxing)</li>
     *   <li>Logging only on state transitions (not every tick)</li>
     * </ul>
     */
    private void checkAndTriggerCumulativeExitFast() {
        // HFT: Use pre-cached array to avoid iterator allocation
        final LegMonitor[] legs = cachedLegsArray;
        // HFT: Combined null/empty check - cachedLegsCount is always 0 when legs is null
        final int count = cachedLegsCount;
        if (count == 0) {
            return;
        }

        // HFT: Cache volatile reads once at start - avoid repeated volatile access
        final double targetPts = cumulativeTargetPoints;
        final double stopPts = cumulativeStopPoints;
        final double negativeStopPts = -stopPts;  // Pre-compute negation once

        // HFT: Inline cumulative calculation with indexed loop
        // Cache direction multiplier in local for faster access in loop
        final double dirMult = directionMultiplier;
        double cumulative = 0.0;
        for (int i = 0; i < count; i++) {
            // HFT: Direct array access is faster than method call
            final double entryPx = legs[i].entryPrice;
            final double currentPx = legs[i].currentPrice;
            cumulative += (currentPx - entryPx) * dirMult;
        }

        // HFT: Lazy debug logging - only build string when debug is enabled
        // Using separate ThreadLocal to avoid conflict with exit reason builder
        if (log.isDebugEnabled()) {
            final StringBuilder legPrices = DEBUG_LOG_BUILDER.get();
            legPrices.setLength(0);
            for (int i = 0; i < count; i++) {
                if (i > 0) legPrices.append(", ");
                legPrices.append(legs[i].symbol).append('=').append(legs[i].currentPrice);
            }
            if(premiumBasedExitEnabled) {
                log.debug("Cumulative P&L for {}: {} points (targetDecayPct: {}, stopLossExpansionPct: {}) | Legs: [{}]",
                        executionId, cumulative, this.targetDecayPct, this.stopLossExpansionPct, legPrices);
            } else {
                log.debug("Cumulative P&L for {}: {} points (target: {}, stop: {}) | Legs: [{}]",
                        executionId, cumulative, targetPts, stopPts, legPrices);
            }
        }

        // ==================== PRIORITY 0: TIME-BASED FORCED EXIT (HIGHEST PRIORITY) ====================
        // Check if we've passed the forced exit cutoff time (IST).
        // This bypasses all P&L checks and forces immediate exit.
        // Idempotent: forcedExitTriggered flag prevents duplicate triggers.
        if (forcedExitEnabled && !forcedExitTriggered) {
            if (isAfterForcedExitTime()) {
                forcedExitTriggered = true;  // Prevent duplicate triggers
                log.warn("TIME-BASED FORCED EXIT for execution {}: current time >= {} IST, P&L={} points - Closing ALL legs",
                        executionId, forcedExitTime, formatDouble(cumulative));
                triggerExitAllLegs(buildExitReasonForcedTime());
                return;
            }
        }

        // ==================== PRIORITY 0.5: PREMIUM-BASED EXIT (WHEN ENABLED) ====================
        // Dynamic premium-based exits based on combined LTP relative to entry premium.
        // This takes precedence over fixed-point MTM checks when enabled.
        if (premiumBasedExitEnabled) {
            // Calculate combined current premium (sum of all leg current prices)
            double combinedLTP = 0.0;
            for (int i = 0; i < count; i++) {
                combinedLTP += legs[i].currentPrice;
            }

            // HFT: Cache pre-computed threshold levels for fast comparison
            final double targetLevel = targetPremiumLevel;
            final double slLevel = stopLossPremiumLevel;

            // HFT: Lazy debug logging for premium mode
            if (log.isDebugEnabled()) {
                log.debug("Premium check for {}: combinedLTP={}, entryPremium={}, targetLevel={}, slLevel={}",
                        executionId, formatDouble(combinedLTP), formatDouble(entryPremium),
                        formatDouble(targetLevel), formatDouble(slLevel));
            }

            // PREMIUM TARGET: Combined LTP has decayed below target level (profit for SHORT)
            // For SHORT straddle: We sold premium, so lower LTP = profit
            if (combinedLTP <= targetLevel) {
                log.warn("PREMIUM_DECAY_TARGET_HIT for execution {}: combinedLTP={}, targetLevel={}, entryPremium={} - Closing ALL legs",
                        executionId, formatDouble(combinedLTP), formatDouble(targetLevel), formatDouble(entryPremium));
                triggerExitAllLegs(buildExitReasonPremiumDecayTarget(combinedLTP, entryPremium, targetLevel));
                return;
            }

            // PREMIUM STOP LOSS: Combined LTP has expanded above stop loss level (loss for SHORT)
            // For SHORT straddle: We sold premium, so higher LTP = loss
            if (combinedLTP >= slLevel) {
                log.warn("PREMIUM_EXPANSION_SL_HIT for execution {}: combinedLTP={}, slLevel={}, entryPremium={} - Closing ALL legs",
                        executionId, formatDouble(combinedLTP), formatDouble(slLevel), formatDouble(entryPremium));
                triggerExitAllLegs(buildExitReasonPremiumExpansionSL(combinedLTP, entryPremium, slLevel));
                return;
            }

            // ==================== PREMIUM-BASED INDIVIDUAL LEG ADJUSTMENT ====================
            // When combinedLTP reaches half the distance between entryPremium and slLevel:
            // 1. Exit the profitable leg (the one with lower current price for SHORT)
            // 2. Add new leg with similar premium to the loss-making leg
            // 3. Adjust target and stop-loss levels for the new combined premium
            // 4. Continue strategy with adjusted legs
            //
            // Half threshold formula: entryPremium + (slLevel - entryPremium) / 2
            // This represents the midpoint between entry and stop-loss expansion
            if (count >= 2 && individualLegExitCallback != null) {
                final double halfThreshold = entryPremium + (slLevel - entryPremium) / 2.0;

                if (combinedLTP >= halfThreshold) {
                    // Find profitable and loss-making legs
                    // For SHORT: profitable leg has lower current price (premium decayed)
                    //            loss-making leg has higher current price (premium expanded)
                    LegMonitor profitableLeg = null;
                    LegMonitor lossMakingLeg = null;
                    double profitableLegPnl = Double.NEGATIVE_INFINITY;
                    double lossMakingLegPnl = Double.POSITIVE_INFINITY;

                    for (int i = 0; i < count; i++) {
                        final LegMonitor leg = legs[i];
                        // For SHORT: P&L = (entry - current) * directionMultiplier
                        // Since directionMultiplier = -1 for SHORT:
                        // P&L = (current - entry) * (-1) = entry - current
                        final double legPnl = (leg.currentPrice - leg.entryPrice) * directionMultiplier;

                        if (legPnl > profitableLegPnl) {
                            profitableLegPnl = legPnl;
                            profitableLeg = leg;
                        }
                        if (legPnl < lossMakingLegPnl) {
                            lossMakingLegPnl = legPnl;
                            lossMakingLeg = leg;
                        }
                    }

                    // Only proceed if we found distinct profitable and loss-making legs
                    if (profitableLeg != null && lossMakingLeg != null && profitableLeg != lossMakingLeg) {
                        log.warn("PREMIUM_LEG_ADJUSTMENT for execution {}: combinedLTP={} reached halfThreshold={} " +
                                        "(slLevel={}, entryPremium={}) - Exiting profitable leg {} and adding replacement",
                                executionId, formatDouble(combinedLTP), formatDouble(halfThreshold),
                                formatDouble(slLevel), formatDouble(entryPremium), profitableLeg.symbol);

                        // Build exit reason for the profitable leg
                        String legExitReason = buildExitReasonPremiumLegAdjustment(
                                profitableLeg.symbol, combinedLTP, halfThreshold, entryPremium);

                        // Calculate target premium for the new leg (similar to loss-making leg's current price)
                        final double targetPremiumForNewLeg = lossMakingLeg.currentPrice;
                        final String exitedLegType = profitableLeg.type;
                        final String newLegType = exitedLegType; // Same type (CE or PE) as the exited leg

                        // Trigger exit callback for the profitable leg
                        individualLegExitCallback.accept(profitableLeg.symbol, legExitReason);

                        // Remove the profitable leg from monitoring
                        legsBySymbol.remove(profitableLeg.symbol);
                        legsByInstrumentToken.remove(profitableLeg.instrumentToken);

                        log.info("Profitable leg {} removed from monitoring for execution {}. Loss-making leg {} current price: {}",
                                profitableLeg.symbol, executionId, lossMakingLeg.symbol, formatDouble(lossMakingLeg.currentPrice));

                        // Trigger leg replacement callback if available
                        if (legReplacementCallback != null) {
                            log.info("Requesting new {} leg with target premium {} (similar to loss-making leg {})",
                                    newLegType, formatDouble(targetPremiumForNewLeg), lossMakingLeg.symbol);
                            legReplacementCallback.onLegReplacement(
                                    profitableLeg.symbol, newLegType, targetPremiumForNewLeg, lossMakingLeg.symbol);
                        }

                        // Rebuild cached arrays after leg removal (new leg will be added via addLeg() call)
                        rebuildCachedLegsArray();

                        // Recalculate combined premium with remaining leg(s)
                        // New entry premium = loss-making leg's current price + target premium for new leg
                        // This will be adjusted when the new leg is added via updateEntryPremiumAfterLegReplacement()
                        log.info("Leg adjustment complete for execution {}. Awaiting new leg addition to adjust thresholds.",
                                executionId);

                        // Continue monitoring with remaining legs
                        // The thresholds will be adjusted when the new leg is added
                        return;
                    }
                }
            }

            // If premium-based exit is enabled, skip fixed-point MTM checks
            // This ensures clean separation between the two modes
            return;
        }

        // ==================== FIXED-POINT MTM EXITS (DEFAULT MODE) ====================
        // Below checks only execute when premiumBasedExitEnabled = false

        // ==================== PRIORITY 1: CUMULATIVE TARGET (FULL EXIT) ====================
        // Check target hit (profit) - highest priority, most likely exit in profitable strategies
        if (cumulative >= targetPts) {
            log.warn("Cumulative target hit for execution {}: cumulative={} points, target={} - Closing ALL legs",
                    executionId, formatDouble(cumulative), formatDouble(targetPts));
            triggerExitAllLegs(buildExitReasonTarget(cumulative));
            return;
        }

        // ==================== PRIORITY 2: INDIVIDUAL LEG STOP LOSS (SHORT ONLY) ====================
        // HFT: Single branch check - only evaluate for SHORT strategies
        // Individual leg exit: if any leg moves +5 points against position → exit that leg only
        /*if (direction == PositionDirection.SHORT && individualLegExitCallback != null && count > 0) {
            for (int i = 0; i < count; i++) {
                final LegMonitor leg = legs[i];

                // Calculate individual leg P&L for SHORT position
                // For SHORT: if price increases, P&L becomes negative
                // Example: entry=100, current=105 → rawDiff=+5 → P&L=-5 (loss)
                final double rawDiff = leg.currentPrice - leg.entryPrice;
                final double legPnl = rawDiff * directionMultiplier; // directionMultiplier = -1 for SHORT

                // Exit condition: leg moved +5 points against us (legPnl <= -5.0)
                if (legPnl <= -cumulativeStopPoints) {
                    log.warn("Individual leg stop loss hit for execution {}: symbol={}, entry={}, current={}, P&L={} points - Exiting this leg only",
                            executionId, leg.symbol, formatDouble(leg.entryPrice),
                            formatDouble(leg.currentPrice), formatDouble(legPnl));

                    // Build exit reason for this specific leg
                    String legExitReason = buildExitReasonIndividualLegStop(leg.symbol, legPnl);

                    // Trigger exit callback for this leg only (using orderId as first parameter)
                    individualLegExitCallback.accept(leg.symbol, legExitReason);

                    // Remove this leg from monitoring
                    legsBySymbol.remove(leg.symbol);
                    legsByInstrumentToken.remove(leg.instrumentToken);

                    // Rebuild cached arrays after leg removal
                    rebuildCachedLegsArray();

                    log.info("Leg {} removed from monitoring. Remaining legs: {}",
                            leg.symbol, legsBySymbol.keySet());

                    // Adjust target for remaining leg(s): new target = original target + stop-loss points
                    // This allows remaining legs to compensate for the loss incurred by the exited leg
                    // HFT: Read volatile once, compute, then write back for atomic-like behavior
                    double previousTarget = cumulativeTargetPoints;
                    double previousStopPoints = cumulativeStopPoints;
                    double newTarget = previousTarget + cumulativeStopPoints;
                    double newStopPoints = previousStopPoints - cumulativeStopPoints;
                    cumulativeTargetPoints = newTarget;
                    cumulativeStopPoints = newStopPoints;

                    log.info("Target adjusted for execution {} after leg {} exit: previous target={} points, new target={} points (added {} stop-loss points)",
                            executionId, leg.symbol, formatDouble(previousTarget),
                            formatDouble(newTarget), formatDouble(cumulativeStopPoints));

                    // Continue monitoring remaining legs - don't return here
                    // This allows cumulative logic to apply to remaining legs
                    break; // Exit loop after removing one leg (process one at a time for safety)
                }
            }
        }

        // ==================== PRIORITY 3: TRAILING STOP LOSS (FULL EXIT) ====================
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
        }*/

        // ==================== PRIORITY 4: FIXED CUMULATIVE STOP LOSS (FULL EXIT) ====================
        // Check fixed stoploss hit (loss) - fallback when trailing not active or not hit
        // HFT: Use pre-computed negative value to avoid negation on every tick
        if (cumulative <= negativeStopPts) {
            log.warn("Cumulative stoploss hit for execution {}: cumulative={} points, stopLoss={} - Closing ALL legs",
                    executionId, formatDouble(cumulative), formatDouble(stopPts));
            triggerExitAllLegs(buildExitReasonStoploss(cumulative));
        }
    }


    /**
     * Triggers exit for all legs by invoking the registered exit callback.
     * <p>
     * This method:
     * <ul>
     *   <li>Deactivates the monitor (prevents duplicate exits)</li>
     *   <li>Records the exit reason</li>
     *   <li>Invokes the exit callback if configured</li>
     * </ul>
     *
     * @param reason exit reason string (formatted by build*ExitReason methods)
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
     * Stops monitoring and prevents further exit evaluations.
     * <p>
     * This method is idempotent - can be called multiple times safely.
     * After calling this method, price updates will be ignored.
     */
    public void stop() {
        active = false;
        log.info("Stopped monitoring for execution: {}", executionId);
    }

    // ==================== HFT OPTIMIZATION: Fast String Building Methods ====================

    /**
     * Normalize percentage value to decimal fraction.
     * <p>
     * Handles both formats:
     * <ul>
     *   <li>Values > 1 are treated as percentages (e.g., 5 → 0.05, 10 → 0.10)</li>
     *   <li>Values <= 1 are treated as already normalized (e.g., 0.05 stays 0.05)</li>
     *   <li>Values <= 0 use the default value</li>
     * </ul>
     *
     * @param value the percentage value (1-100) or decimal fraction (0.01-1.0)
     * @param defaultPct the default percentage value (1-100) if value <= 0
     * @return normalized decimal fraction (0.01-1.0)
     */
    private static double normalizePercentage(double value, double defaultPct) {
        if (value <= 0) {
            // Use default and normalize it
            return defaultPct / 100.0;
        }
        if (value > 1.0) {
            // Value is in percentage form (e.g., 5 for 5%), convert to decimal
            return value / 100.0;
        }
        // Value is already a decimal fraction (e.g., 0.05 for 5%)
        return value;
    }

    /**
     * HFT: Fast double formatting without String.format overhead.
     * Uses ThreadLocal StringBuilder to avoid allocation on hot path.
     *
     * @param value the double value to format
     * @return string representation with 2 decimal places (e.g., "3.14", "-2.50")
     */
    private static String formatDouble(double value) {
        // HFT: Uses dedicated ThreadLocal to avoid conflict with exit reason builders
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

    /**
     * HFT: Build target exit reason without String.format.
     *
     * @param cumulative current cumulative P&amp;L in points
     * @return formatted exit reason string
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
     *
     * @param cumulative current cumulative P&amp;L in points
     * @return formatted exit reason string
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
     * Includes P&amp;L, high-water mark, and trail level for full context.
     *
     * @param cumulative current cumulative P&amp;L in points
     * @param hwm high-water mark (best P&amp;L achieved) in points
     * @param trailLevel current trailing stop level in points
     * @return formatted exit reason string
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
     * HFT: Build individual leg stop loss exit reason without String.format.
     * Used when a single leg hits its stop loss threshold.
     *
     * @param symbol trading symbol of the exited leg
     * @param legPnl P&amp;L of the individual leg in points
     * @return formatted exit reason string
     */
    private static String buildExitReasonIndividualLegStop(String symbol, double legPnl) {
        StringBuilder sb = EXIT_REASON_BUILDER.get();
        sb.setLength(0);
        sb.append(EXIT_PREFIX_INDIVIDUAL_LEG_STOP);
        sb.append(symbol);
        sb.append(EXIT_SUFFIX_PNL);
        appendDouble(sb, legPnl);
        sb.append(EXIT_SUFFIX_POINTS);
        return sb.toString();
    }

    /**
     * Build exit reason for time-based forced exit.
     * <p>
     * Format: "TIME_BASED_FORCED_EXIT @ 15:10"
     *
     * @return formatted exit reason string with cutoff time
     */
    private String buildExitReasonForcedTime() {
        StringBuilder sb = EXIT_REASON_BUILDER.get();
        sb.setLength(0);
        sb.append(EXIT_PREFIX_FORCED_TIME);
        // Format time as HH:mm
        int hour = forcedExitTime.getHour();
        int minute = forcedExitTime.getMinute();
        if (hour < 10) sb.append('0');
        sb.append(hour);
        sb.append(':');
        if (minute < 10) sb.append('0');
        sb.append(minute);
        return sb.toString();
    }

    /**
     * Build exit reason for premium decay target hit.
     * <p>
     * Format: "PREMIUM_DECAY_TARGET_HIT (Combined LTP: X.XX, Entry: Y.YY, TargetLevel: Z.ZZ)"
     *
     * @param combinedLTP current combined premium (CE + PE LTP)
     * @param entry original entry premium
     * @param targetLevel target premium threshold
     * @return formatted exit reason string
     */
    private String buildExitReasonPremiumDecayTarget(double combinedLTP, double entry, double targetLevel) {
        StringBuilder sb = EXIT_REASON_BUILDER.get();
        sb.setLength(0);
        sb.append(EXIT_PREFIX_PREMIUM_DECAY);
        appendDouble(sb, combinedLTP);
        sb.append(EXIT_SUFFIX_ENTRY);
        appendDouble(sb, entry);
        sb.append(EXIT_SUFFIX_TARGET_LEVEL);
        appendDouble(sb, targetLevel);
        sb.append(EXIT_SUFFIX_CLOSE);
        return sb.toString();
    }

    /**
     * Build exit reason for premium expansion stop loss hit.
     * <p>
     * Format: "PREMIUM_EXPANSION_SL_HIT (Combined LTP: X.XX, Entry: Y.YY, SL Level: Z.ZZ)"
     *
     * @param combinedLTP current combined premium (CE + PE LTP)
     * @param entry original entry premium
     * @param slLevel stop loss premium threshold
     * @return formatted exit reason string
     */
    private String buildExitReasonPremiumExpansionSL(double combinedLTP, double entry, double slLevel) {
        StringBuilder sb = EXIT_REASON_BUILDER.get();
        sb.setLength(0);
        sb.append(EXIT_PREFIX_PREMIUM_EXPANSION);
        appendDouble(sb, combinedLTP);
        sb.append(EXIT_SUFFIX_ENTRY);
        appendDouble(sb, entry);
        sb.append(EXIT_SUFFIX_SL_LEVEL);
        appendDouble(sb, slLevel);
        sb.append(EXIT_SUFFIX_CLOSE);
        return sb.toString();
    }

    /**
     * Build exit reason for premium-based individual leg adjustment.
     * <p>
     * Format: "PREMIUM_LEG_ADJUSTMENT (Profitable leg: SYMBOL, CombinedLTP: X.XX, HalfThreshold: Y.YY, Entry: Z.ZZ)"
     * <p>
     * This is used when combinedLTP reaches half the distance between entry and SL level,
     * triggering an exit of the profitable leg and replacement with a new leg.
     *
     * @param profitableLegSymbol symbol of the profitable leg being exited
     * @param combinedLTP current combined premium (CE + PE LTP)
     * @param halfThreshold the half-threshold level that triggered the adjustment
     * @param entry original entry premium
     * @return formatted exit reason string
     */
    private String buildExitReasonPremiumLegAdjustment(String profitableLegSymbol, double combinedLTP,
                                                        double halfThreshold, double entry) {
        StringBuilder sb = EXIT_REASON_BUILDER.get();
        sb.setLength(0);
        sb.append(EXIT_PREFIX_PREMIUM_LEG_ADJUSTMENT);
        sb.append(profitableLegSymbol);
        sb.append(EXIT_SUFFIX_COMBINED_LTP);
        appendDouble(sb, combinedLTP);
        sb.append(EXIT_SUFFIX_HALF_THRESHOLD);
        appendDouble(sb, halfThreshold);
        sb.append(EXIT_SUFFIX_ENTRY);
        appendDouble(sb, entry);
        sb.append(EXIT_SUFFIX_CLOSE);
        return sb.toString();
    }

    /**
     * Check if current time (IST) is at or after the forced exit cutoff time.
     * <p>
     * Thread-safe and uses IST timezone for exchange time comparison.
     *
     * @return true if current time >= forced exit time, false otherwise
     */
    private boolean isAfterForcedExitTime() {
        LocalTime now = ZonedDateTime.now(IST).toLocalTime();
        return !now.isBefore(forcedExitTime);
    }

    /**
     * Check if forced exit time has been reached for external callers (e.g., backtest).
     * <p>
     * This method allows backtesting systems to check forced exit condition using
     * a simulated time instead of the current system time.
     *
     * @param simulatedTime the simulated current time to check against
     * @return true if simulatedTime >= forced exit time and forced exit is enabled
     */
    public boolean shouldForcedExit(LocalTime simulatedTime) {
        if (!forcedExitEnabled || forcedExitTriggered) {
            return false;
        }
        return !simulatedTime.isBefore(forcedExitTime);
    }

    /**
     * Manually trigger forced exit (for backtest or external triggers).
     * <p>
     * This method is idempotent - calling multiple times has no additional effect.
     *
     * @return true if exit was triggered, false if already triggered or inactive
     */
    public boolean triggerForcedExit() {
        if (!active || forcedExitTriggered) {
            return false;
        }
        forcedExitTriggered = true;
        log.warn("MANUAL FORCED EXIT triggered for execution {}: cutoff time={} IST - Closing ALL legs",
                executionId, forcedExitTime);
        triggerExitAllLegs(buildExitReasonForcedTime());
        return true;
    }

    /**
     * HFT: Append double with 2 decimal places to StringBuilder.
     *
     * @param sb StringBuilder to append to
     * @param value double value to format and append
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
     * Get all legs - returns cached immutable list for performance.
     * <p>
     * The list is cached and only rebuilt when legs are added or removed.
     * This avoids repeated allocations for frequent read access.
     *
     * @return immutable list of all legs currently monitored
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
     * Get legs map by symbol - for checking if specific legs are still active.
     *
     * @return concurrent map of legs indexed by trading symbol
     */
    public Map<String, LegMonitor> getLegsBySymbol() {
        return legsBySymbol;
    }

    // ==================== TRAILING STOP LOSS GETTERS ====================

    /**
     * Get the current high-water mark (best P&L achieved).
     *
     * @return high-water mark in points
     */
    public double getHighWaterMark() {
        return highWaterMark;
    }

    /**
     * Get the current trailing stop level.
     *
     * @return trailing stop level in points, or Double.NEGATIVE_INFINITY if not activated
     */
    public double getCurrentTrailingStopLevel() {
        return currentTrailingStopLevel;
    }

    /**
     * Check if trailing stop has been activated.
     *
     * @return true if trailing stop is active
     */
    public boolean isTrailingStopActivated() {
        return trailingStopActivated;
    }

    /**
     * Get trailing stop activation threshold.
     *
     * @return activation threshold in points
     */
    public double getTrailingActivationPoints() {
        return trailingActivationPoints;
    }

    /**
     * Get trailing stop distance.
     *
     * @return trail distance in points
     */
    public double getTrailingDistancePoints() {
        return trailingDistancePoints;
    }

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
         * Get P&L for this leg using primitive arithmetic.
         *
         * @return raw P&L = (currentPrice - entryPrice) * quantity
         */
        public double getPnl() {
            return (currentPrice - entryPrice) * quantity;
        }
    }
}
