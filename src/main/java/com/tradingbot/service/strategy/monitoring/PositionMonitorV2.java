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

    /** Ordered list of exit strategies (immutable after construction, for external access) */
    private final List<ExitStrategy> exitStrategies;

    /** HFT: Array copy of exit strategies for indexed iteration (no Iterator allocation on hot path) */
    private final ExitStrategy[] exitStrategiesArray;

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
    /** HFT: Volatile reference swap pattern — lock-free reads on hot path, rebuilt on addLeg/removeLeg */
    private volatile LongObjectHashMap<LegMonitor> legsByInstrumentToken = new LongObjectHashMap<>(4);

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

    /**
     * Flag indicating a leg replacement is in progress.
     * <p>
     * When true, exit condition evaluation is paused to prevent conflicting
     * decisions while waiting for the replacement leg to be placed.
     * <p>
     * HFT: Uses volatile for thread visibility without lock overhead.
     */
    @Getter
    private volatile boolean legReplacementInProgress = false;

    /**
     * Symbol of the leg being replaced (for logging/debugging).
     */
    private volatile String legBeingReplaced;

    /**
     * Timestamp when leg replacement started (for timeout detection).
     */
    private volatile long legReplacementStartTimeNanos;

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

    /** HFT: Pre-allocated mutable context — reset and reused on every tick to avoid allocation */
    private final ExitContext reusableExitContext;

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
     * <p>
     * Called when a profitable leg is exited and needs to be replaced with a new leg.
     * The replacement leg selection should:
     * <ul>
     *   <li>Not be the same symbol as the exited leg</li>
     *   <li>Have LTP greater than the exited leg's LTP</li>
     * </ul>
     */
    @FunctionalInterface
    public interface LegReplacementCallback {
        /**
         * Called when a leg needs to be replaced.
         *
         * @param exitedLegSymbol symbol of the leg that was exited (should be excluded from selection)
         * @param legTypeToAdd type of leg to add (CE or PE)
         * @param targetPremium target premium for the new leg
         * @param lossMakingLegSymbol symbol of the loss-making leg (for reference)
         * @param exitedLegLtp LTP of the exited leg at time of exit (replacement must have LTP > this)
         */
        void onLegReplacement(String exitedLegSymbol, String legTypeToAdd,
                              double targetPremium, String lossMakingLegSymbol, double exitedLegLtp);
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
        this.exitStrategiesArray = this.exitStrategies.toArray(new ExitStrategy[0]);

        // HFT: Pre-allocate reusable ExitContext — will be mutated on every tick instead of creating new instances
        this.reusableExitContext = new ExitContext(
                executionId, this.directionMultiplier, this.direction,
                this.cumulativeTargetPoints, this.cumulativeStopPoints,
                this.entryPremium, this.targetPremiumLevel, this.stopLossPremiumLevel,
                null, 0, null, null
        );

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
        addLeg(orderId, symbol, instrumentToken, entryPrice, quantity, type, 1.0);
    }

    /**
     * Adds a leg with explicit direction multiplier.
     * <p>
     * Use legDirectionMultiplier = 1.0 for legs in the same direction as the monitor (e.g., SELL legs in SHORT).
     * Use legDirectionMultiplier = -1.0 for legs opposite to the monitor (e.g., BUY hedge legs in SHORT).
     *
     * @param orderId            order ID
     * @param symbol             trading symbol
     * @param instrumentToken    instrument token for price lookup
     * @param entryPrice         entry price
     * @param quantity           quantity
     * @param type               leg type (CE, PE)
     * @param legDirectionMultiplier per-leg direction multiplier
     */
    public void addLeg(String orderId, String symbol, long instrumentToken,
                       double entryPrice, int quantity, String type, double legDirectionMultiplier) {
        LegMonitor leg = new LegMonitor(orderId, symbol, instrumentToken, entryPrice, quantity, type, legDirectionMultiplier);
        legsBySymbol.put(symbol, leg);
        rebuildCachedLegsArray();
        rebuildInstrumentTokenMap();
        log.info("Added leg to monitor: {} at entry price: {} (legDirection={})", symbol, entryPrice, legDirectionMultiplier);
    }

    /**
     * Removes a leg from monitoring.
     */
    public void removeLeg(String symbol) {
        LegMonitor leg = legsBySymbol.remove(symbol);
        if (leg != null) {
            rebuildCachedLegsArray();
            rebuildInstrumentTokenMap();
            log.info("Removed leg from monitor: {}", symbol);
        }
    }

    private void rebuildCachedLegsArray() {
        LegMonitor[] newArray = legsBySymbol.values().toArray(new LegMonitor[0]);
        cachedLegsCount = newArray.length;
        cachedLegsArray = newArray;
    }

    /**
     * HFT: Rebuild the instrument token → LegMonitor map as a new volatile reference.
     * This is the write side of the volatile-swap pattern. Called only on addLeg/removeLeg (rare).
     * Reads on the hot path (tick processing) are lock-free.
     */
    private void rebuildInstrumentTokenMap() {
        LongObjectHashMap<LegMonitor> newMap = new LongObjectHashMap<>(legsBySymbol.size() * 2);
        for (LegMonitor leg : legsBySymbol.values()) {
            newMap.put(leg.getInstrumentToken(), leg);
        }
        legsByInstrumentToken = newMap; // volatile write — publishes to tick thread
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
     * This is a convenience method that combines addLeg(), updateEntryPremiumAfterLegReplacement(),
     * and signalLegReplacementComplete() for use after an individual leg exit in premium-based exit mode.
     * <p>
     * <b>Important:</b> This method automatically clears the leg replacement in-progress state,
     * resuming exit condition evaluation. If you need more control, use addLeg() and
     * signalLegReplacementComplete() separately.
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

        // Automatically signal completion to resume exit evaluation
        // This is the key fix - clear the legReplacementInProgress flag
        if (legReplacementInProgress) {
            signalLegReplacementComplete(symbol);
        }
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

        // HFT: Read volatile reference once for the entire tick batch
        final LongObjectHashMap<LegMonitor> tokenMap = legsByInstrumentToken;

        // Update leg prices
        for (int i = 0; i < tickCount; i++) {
            final Tick tick = ticks.get(i);
            final LegMonitor leg = tokenMap.get(tick.getInstrumentToken());
            if (leg != null) {
                leg.setCurrentPrice(tick.getLastTradedPrice());
            }
        }

        // Evaluate exit conditions using strategy pattern
        evaluateExitConditions();
    }

    /**
     * HFT-optimized exit evaluation using strategy pattern.
     * <p>
     * This method skips evaluation when a leg replacement is in progress to prevent
     * conflicting exit decisions while waiting for the replacement order to be placed.
     */
    private void evaluateExitConditions() {
        // Skip evaluation if leg replacement is in progress
        // This prevents conflicting exit decisions while waiting for replacement
        if (legReplacementInProgress) {
            // Check for timeout (30 seconds) to prevent infinite blocking
            long elapsedNanos = System.nanoTime() - legReplacementStartTimeNanos;
            long elapsedSeconds = elapsedNanos / 1_000_000_000L;
            if (elapsedSeconds > 30) {
                log.warn("Leg replacement timeout for {} after {}s - resuming exit evaluation. Leg: {}",
                        executionId, elapsedSeconds, legBeingReplaced);
                clearLegReplacementState();
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Skipping exit evaluation for {} - leg replacement in progress for {}",
                            executionId, legBeingReplaced);
                }
                return;
            }
        }

        final LegMonitor[] legs = cachedLegsArray;
        final int count = cachedLegsCount;
        if (count == 0) return;

        // Calculate cumulative P&L
        // Uses per-leg direction multiplier to handle mixed-direction strategies
        // (e.g., short strangle: SELL main legs + BUY hedge legs)
        double cumulative = 0.0;
        for (int i = 0; i < count; i++) {
            cumulative += (legs[i].getCurrentPrice() - legs[i].getEntryPrice())
                    * directionMultiplier * legs[i].getLegDirectionMultiplier();
        }

        // Debug logging (guarded to avoid StringBuilder/formatDouble overhead when disabled)
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

        // HFT: Reuse pre-allocated ExitContext — zero allocation on hot path
        reusableExitContext.resetForTick(
                cumulativeTargetPoints, cumulativeStopPoints,
                entryPremium, targetPremiumLevel, stopLossPremiumLevel,
                legs, count,
                individualLegExitCallback, legReplacementCallback
        );
        reusableExitContext.setCumulativePnL(cumulative);

        // HFT: Evaluate strategies using indexed array loop (no Iterator allocation)
        final ExitStrategy[] strategies = exitStrategiesArray;
        final int strategyCount = strategies.length;
        for (int i = 0; i < strategyCount; i++) {
            final ExitStrategy strategy = strategies[i];
            if (!strategy.isEnabled(reusableExitContext)) continue;

            ExitResult result = strategy.evaluate(reusableExitContext);

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
                // Set leg replacement state BEFORE triggering callback
                // This ensures evaluateExitConditions will skip evaluation on next tick
                setLegReplacementState(result.getLegSymbol());

                // Update the loss-making leg's entry price to its current LTP
                final String lossMakingLegSymbol = result.getLossMakingLegSymbol();
                final double newEntryPrice = result.getLossMakingLegNewEntryPrice();
                if (lossMakingLegSymbol != null && newEntryPrice > 0.0) {
                    LegMonitor lossMakingLeg = legsBySymbol.get(lossMakingLegSymbol);
                    if (lossMakingLeg != null) {
                        double oldEntryPrice = lossMakingLeg.getEntryPrice();
                        lossMakingLeg.setEntryPrice(newEntryPrice);
                        log.info("Updated entry price for {} from {} to {} during leg replacement",
                                lossMakingLegSymbol, formatDouble(oldEntryPrice), formatDouble(newEntryPrice));
                    }
                }

                if (individualLegExitCallback != null) {
                    individualLegExitCallback.accept(result.getLegSymbol(), result.getExitReason());
                }
                removeLeg(result.getLegSymbol());

                if (legReplacementCallback != null) {
                    legReplacementCallback.onLegReplacement(
                            result.getLegSymbol(),
                            result.getNewLegType(),
                            result.getTargetPremiumForNewLeg(),
                            result.getLossMakingLegSymbol(),
                            result.getExitedLegLtp()  // Pass the exited leg's LTP for replacement validation
                    );
                } else {
                    // No callback registered - clear state to avoid blocking
                    log.warn("No legReplacementCallback registered for {} - clearing replacement state", executionId);
                    clearLegReplacementState();
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

    // ==================== LEG REPLACEMENT STATE MANAGEMENT ====================

    /**
     * Sets the leg replacement in-progress state.
     * <p>
     * Called internally when ADJUST_LEG exit result is triggered.
     * This pauses exit evaluation until the replacement leg is placed.
     *
     * @param legSymbol the symbol of the leg being replaced
     */
    private void setLegReplacementState(String legSymbol) {
        this.legBeingReplaced = legSymbol;
        this.legReplacementStartTimeNanos = System.nanoTime();
        this.legReplacementInProgress = true;
        log.info("Leg replacement started for {} - pausing exit evaluation until replacement is complete. Leg: {}",
                executionId, legSymbol);
    }

    /**
     * Clears the leg replacement in-progress state.
     * <p>
     * Called after replacement is complete or on timeout.
     */
    private void clearLegReplacementState() {
        this.legReplacementInProgress = false;
        this.legBeingReplaced = null;
        this.legReplacementStartTimeNanos = 0;
    }

    /**
     * Signals that the leg replacement has been completed.
     * <p>
     * This method should be called by external code (e.g., SellATMStraddleStrategy)
     * after the replacement leg order has been placed and added to the monitor.
     * Calling this method resumes exit condition evaluation.
     *
     * @param newLegSymbol the symbol of the newly placed replacement leg (for logging)
     */
    public void signalLegReplacementComplete(String newLegSymbol) {
        if (!legReplacementInProgress) {
            log.debug("signalLegReplacementComplete called for {} but no replacement was in progress", executionId);
            return;
        }

        long elapsedNanos = System.nanoTime() - legReplacementStartTimeNanos;
        long elapsedMs = elapsedNanos / 1_000_000L;

        log.info("Leg replacement completed for {} - resuming exit evaluation. " +
                 "Replaced: {} -> New: {}, Duration: {}ms",
                executionId, legBeingReplaced, newLegSymbol, elapsedMs);

        clearLegReplacementState();
    }

    /**
     * Signals that the leg replacement has failed.
     * <p>
     * This method should be called when the replacement leg order fails.
     * Exit evaluation will resume, which may trigger a full position exit.
     *
     * @param reason the reason for failure
     */
    public void signalLegReplacementFailed(String reason) {
        if (!legReplacementInProgress) {
            log.debug("signalLegReplacementFailed called for {} but no replacement was in progress", executionId);
            return;
        }

        log.warn("Leg replacement failed for {} - resuming exit evaluation. Leg: {}, Reason: {}",
                executionId, legBeingReplaced, reason);

        clearLegReplacementState();
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
        if (value >= 1.0) return value / 100.0;
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

