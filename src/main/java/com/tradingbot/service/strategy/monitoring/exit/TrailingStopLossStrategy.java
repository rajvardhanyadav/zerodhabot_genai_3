package com.tradingbot.service.strategy.monitoring.exit;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * HFT-optimized trailing stop loss exit strategy.
 * <p>
 * Implements a dynamic trailing stop that activates after reaching a profit threshold
 * and then follows the high-water mark at a configured distance.
 *
 * <h2>Trailing Stop Logic</h2>
 * <ol>
 *   <li><b>Activation</b>: When cumulative P&L >= activationPoints, trailing begins</li>
 *   <li><b>High-Water Mark (HWM)</b>: Tracks best P&L achieved since activation</li>
 *   <li><b>Trail Level</b>: currentTrailLevel = HWM - distancePoints</li>
 *   <li><b>Exit</b>: When cumulative P&L falls to or below trail level</li>
 * </ol>
 *
 * <h2>HFT Optimizations</h2>
 * <ul>
 *   <li>Volatile fields for thread-safe HWM updates without synchronization</li>
 *   <li>Fast activation check before detailed evaluation</li>
 *   <li>Inline arithmetic with primitives</li>
 *   <li>Pre-built exit reason prefixes</li>
 * </ul>
 *
 * @see ExitStrategy
 */
@Slf4j
public class TrailingStopLossStrategy extends AbstractExitStrategy {

    /** Priority: 300 (evaluated after target but before fixed stop loss) */
    private static final int PRIORITY = 300;

    // HFT: Pre-built exit reason components
    private static final String EXIT_PREFIX = "TRAILING_STOPLOSS_HIT (P&L: ";
    private static final String EXIT_HWM = ", HighWaterMark: ";
    private static final String EXIT_TRAIL_LEVEL = ", TrailLevel: ";
    private static final String EXIT_SUFFIX = " points)";

    /** P&L threshold to activate trailing (e.g., 1.0 = activate after 1 point profit) */
    private final double activationPoints;

    /** Distance trailing stop follows behind high-water mark */
    private final double distancePoints;

    /** High-water mark: best cumulative P&L achieved since activation */
    @Getter
    private volatile double highWaterMark = 0.0;

    /** Current trailing stop level (dynamic): HWM - distancePoints */
    @Getter
    private volatile double currentTrailingStopLevel = Double.NEGATIVE_INFINITY;

    /** Flag indicating if trailing stop has been activated */
    @Getter
    private volatile boolean activated = false;

    /**
     * Creates a trailing stop loss strategy.
     *
     * @param activationPoints P&L threshold to activate trailing (e.g., 1.0)
     * @param distancePoints distance trail follows behind HWM (e.g., 0.5)
     */
    public TrailingStopLossStrategy(double activationPoints, double distancePoints) {
        this.activationPoints = activationPoints > 0 ? activationPoints : 1.0;
        this.distancePoints = distancePoints > 0 ? distancePoints : 0.5;
        log.debug("TrailingStopLossStrategy created: activation={}, distance={}",
                this.activationPoints, this.distancePoints);
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public String getName() {
        return "TrailingStopLoss";
    }

    @Override
    public ExitResult evaluate(ExitContext ctx) {
        final double cumulative = ctx.getCumulativePnL();

        // HFT: Check trailing stop hit FIRST (most common check when activated)
        // This ordering optimizes for the steady-state case where HWM isn't changing
        if (activated) {
            // Check if trailing stop was hit
            if (cumulative <= currentTrailingStopLevel) {
                log.warn("Trailing stoploss hit for execution {}: P&L={} points, HWM={}, trailLevel={} - Closing ALL legs",
                        ctx.getExecutionId(), formatDouble(cumulative), formatDouble(highWaterMark),
                        formatDouble(currentTrailingStopLevel));
                return ExitResult.exitAll(buildExitReason(cumulative, highWaterMark, currentTrailingStopLevel));
            }

            // HFT: Update HWM only if we have a new peak (branch predicted as unlikely)
            if (cumulative > highWaterMark) {
                highWaterMark = cumulative;
                // HFT: Single arithmetic operation, no branching
                currentTrailingStopLevel = cumulative - distancePoints;
            }
        } else {
            // Not yet activated - check for activation condition
            if (cumulative >= activationPoints) {
                // HFT: Activation is rare (happens once), OK to have logging here
                highWaterMark = cumulative;
                currentTrailingStopLevel = cumulative - distancePoints;
                activated = true;
                log.info("Trailing stop ACTIVATED for execution {}: HWM={} points, trailLevel={} points",
                        ctx.getExecutionId(), formatDouble(highWaterMark), formatDouble(currentTrailingStopLevel));
            }
        }

        return ExitResult.noExit();
    }

    /**
     * Build trailing stop exit reason.
     */
    private String buildExitReason(double cumulative, double hwm, double trailLevel) {
        StringBuilder sb = getExitReasonBuilder();
        sb.append(EXIT_PREFIX);
        appendDouble(sb, cumulative);
        sb.append(EXIT_HWM);
        appendDouble(sb, hwm);
        sb.append(EXIT_TRAIL_LEVEL);
        appendDouble(sb, trailLevel);
        sb.append(EXIT_SUFFIX);
        return sb.toString();
    }

    /**
     * Reset strategy state (for testing/reuse).
     */
    public void reset() {
        highWaterMark = 0.0;
        currentTrailingStopLevel = Double.NEGATIVE_INFINITY;
        activated = false;
    }

    /**
     * Get activation threshold.
     *
     * @return activation points
     */
    public double getActivationPoints() {
        return activationPoints;
    }

    /**
     * Get trailing distance.
     *
     * @return distance points
     */
    public double getDistancePoints() {
        return distancePoints;
    }
}

