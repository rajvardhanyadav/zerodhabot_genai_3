package com.tradingbot.service.strategy.monitoring.exit;

import com.tradingbot.service.strategy.monitoring.LegMonitor;
import com.tradingbot.service.strategy.monitoring.PositionMonitorV2;
import lombok.Getter;
import lombok.Setter;

import java.util.function.BiConsumer;

/**
 * HFT-optimized context holder for exit evaluation.
 * <p>
 * Passed to {@link ExitStrategy#evaluate(ExitContext)} to avoid parameter explosion
 * and enable pre-computation of shared values. Designed for zero allocation on the hot path
 * by being reused across strategy evaluations within a single tick processing cycle.
 *
 * <h2>Thread Safety</h2>
 * <ul>
 *   <li>Instance is created per-tick evaluation (stack allocated effectively)</li>
 *   <li>Not shared across threads - used only within single tick processing</li>
 *   <li>References to shared state (legs array) are read-only snapshots</li>
 * </ul>
 *
 * <h2>HFT Optimizations</h2>
 * <ul>
 *   <li>All fields are primitives or final references (no autoboxing)</li>
 *   <li>Pre-computed cumulative P&L and combined LTP to avoid recalculation</li>
 *   <li>Direct array reference (no defensive copy) for maximum performance</li>
 * </ul>
 */
@Getter
public class ExitContext {

    // ==================== STATIC CONFIGURATION (set once) ====================

    /** Unique execution identifier for logging */
    private final String executionId;

    /** Direction multiplier: 1.0 for LONG, -1.0 for SHORT */
    private final double directionMultiplier;

    /** Position direction */
    private final PositionMonitorV2.PositionDirection direction;

    // ==================== THRESHOLD CONFIGURATION ====================

    /** Cumulative target points for exit (dynamically adjusted) */
    @Setter
    private double cumulativeTargetPoints;

    /** Cumulative stop points for exit (dynamically adjusted) */
    @Setter
    private double cumulativeStopPoints;

    // ==================== PREMIUM-BASED CONFIGURATION ====================

    /** Entry premium (CE + PE) for premium-based exits */
    @Setter
    private double entryPremium;

    /** Pre-computed target premium level */
    @Setter
    private double targetPremiumLevel;

    /** Pre-computed stop loss premium level */
    @Setter
    private double stopLossPremiumLevel;

    // ==================== LEG STATE (snapshot from PositionMonitor) ====================

    /** Pre-cached legs array (read-only snapshot) */
    @Setter
    private LegMonitor[] legs;

    /** Number of active legs */
    @Setter
    private int legsCount;

    // ==================== COMPUTED VALUES (set during evaluation) ====================

    /** Cumulative P&L across all legs (computed once per tick) */
    @Setter
    private double cumulativePnL;

    /** Combined current LTP of all legs (for premium-based exits) */
    @Setter
    private double combinedLTP;

    // ==================== CALLBACKS ====================

    /** Callback for individual leg exits */
    @Setter
    private BiConsumer<String, String> individualLegExitCallback;

    /** Callback for leg replacement in premium mode */
    @Setter
    private PositionMonitorV2.LegReplacementCallback legReplacementCallback;

    /**
     * Creates an exit context with all required evaluation state.
     *
     * @param executionId unique execution identifier
     * @param directionMultiplier 1.0 for LONG, -1.0 for SHORT
     * @param direction position direction enum
     * @param cumulativeTargetPoints target points threshold
     * @param cumulativeStopPoints stop loss points threshold
     * @param entryPremium combined entry premium
     * @param targetPremiumLevel pre-computed target level
     * @param stopLossPremiumLevel pre-computed stop loss level
     * @param legs cached legs array (snapshot)
     * @param legsCount number of active legs
     * @param individualLegExitCallback callback for individual leg exits
     * @param legReplacementCallback callback for leg replacement
     */
    public ExitContext(String executionId,
                       double directionMultiplier,
                       PositionMonitorV2.PositionDirection direction,
                       double cumulativeTargetPoints,
                       double cumulativeStopPoints,
                       double entryPremium,
                       double targetPremiumLevel,
                       double stopLossPremiumLevel,
                       LegMonitor[] legs,
                       int legsCount,
                       BiConsumer<String, String> individualLegExitCallback,
                       PositionMonitorV2.LegReplacementCallback legReplacementCallback) {
        this.executionId = executionId;
        this.directionMultiplier = directionMultiplier;
        this.direction = direction;
        this.cumulativeTargetPoints = cumulativeTargetPoints;
        this.cumulativeStopPoints = cumulativeStopPoints;
        this.entryPremium = entryPremium;
        this.targetPremiumLevel = targetPremiumLevel;
        this.stopLossPremiumLevel = stopLossPremiumLevel;
        this.legs = legs;
        this.legsCount = legsCount;
        this.individualLegExitCallback = individualLegExitCallback;
        this.legReplacementCallback = legReplacementCallback;
    }

    /**
     * HFT: Check if individual leg exit callback is available.
     *
     * @return true if callback is set
     */
    public boolean hasIndividualLegExitCallback() {
        return individualLegExitCallback != null;
    }

    /**
     * HFT: Check if leg replacement callback is available.
     *
     * @return true if callback is set
     */
    public boolean hasLegReplacementCallback() {
        return legReplacementCallback != null;
    }

    /**
     * HFT: Reset mutable fields for reuse on the next tick.
     * <p>
     * This is used with the pre-allocated ExitContext pattern in PositionMonitorV2
     * to eliminate per-tick object allocation. Static fields (executionId, directionMultiplier,
     * direction) are not reset — they remain from construction.
     *
     * @param cumulativeTargetPoints current target threshold
     * @param cumulativeStopPoints current stop loss threshold
     * @param entryPremium current entry premium
     * @param targetPremiumLevel current target premium level
     * @param stopLossPremiumLevel current stop loss premium level
     * @param legs cached legs array snapshot
     * @param legsCount number of active legs
     * @param individualLegExitCallback leg exit callback
     * @param legReplacementCallback leg replacement callback
     */
    public void resetForTick(double cumulativeTargetPoints, double cumulativeStopPoints,
                             double entryPremium, double targetPremiumLevel, double stopLossPremiumLevel,
                             LegMonitor[] legs, int legsCount,
                             BiConsumer<String, String> individualLegExitCallback,
                             PositionMonitorV2.LegReplacementCallback legReplacementCallback) {
        this.cumulativeTargetPoints = cumulativeTargetPoints;
        this.cumulativeStopPoints = cumulativeStopPoints;
        this.entryPremium = entryPremium;
        this.targetPremiumLevel = targetPremiumLevel;
        this.stopLossPremiumLevel = stopLossPremiumLevel;
        this.legs = legs;
        this.legsCount = legsCount;
        this.individualLegExitCallback = individualLegExitCallback;
        this.legReplacementCallback = legReplacementCallback;
        this.cumulativePnL = 0.0;
        this.combinedLTP = 0.0;
    }
}






