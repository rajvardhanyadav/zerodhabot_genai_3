package com.tradingbot.service.strategy.monitoring.exit;

import lombok.extern.slf4j.Slf4j;

/**
 * HFT-optimized points-based exit strategy for cumulative P&L monitoring.
 * <p>
 * Evaluates fixed-point MTM (Mark-to-Market) exit conditions based on
 * cumulative P&L across all legs in a multi-leg options strategy.
 *
 * <h2>Exit Logic</h2>
 * <ul>
 *   <li><b>Cumulative Target</b>: Exit all legs when total P&L >= targetPoints</li>
 *   <li><b>Cumulative Stop Loss</b>: Exit all legs when total P&L <= -stopLossPoints</li>
 * </ul>
 *
 * <h2>HFT Optimizations</h2>
 * <ul>
 *   <li>Pre-computed cumulative P&L passed via context</li>
 *   <li>Pre-computed negative stop loss for fast comparison</li>
 *   <li>Simple branch structure for predictable execution</li>
 *   <li>Pre-built exit reason prefixes</li>
 * </ul>
 *
 * @see ExitStrategy
 */
@Slf4j
public class PointsBasedExitStrategy extends AbstractExitStrategy {

    /** Priority: 100 for target, 400 for stop loss */
    private static final int TARGET_PRIORITY = 100;
    private static final int STOPLOSS_PRIORITY = 400;

    // HFT: Pre-built exit reason components
    private static final String EXIT_PREFIX_TARGET = "CUMULATIVE_TARGET_HIT (Signal: ";
    private static final String EXIT_PREFIX_STOPLOSS = "CUMULATIVE_STOPLOSS_HIT (Signal: ";
    private static final String EXIT_SUFFIX_POINTS = " points)";

    /** Evaluation mode: TARGET checks profit, STOPLOSS checks loss */
    public enum Mode {
        TARGET,
        STOPLOSS
    }

    private final Mode mode;

    /**
     * Creates a points-based exit strategy.
     *
     * @param mode evaluation mode (TARGET or STOPLOSS)
     */
    public PointsBasedExitStrategy(Mode mode) {
        this.mode = mode;
    }

    /**
     * Factory method for target evaluation strategy.
     *
     * @return new TARGET mode strategy
     */
    public static PointsBasedExitStrategy forTarget() {
        return new PointsBasedExitStrategy(Mode.TARGET);
    }

    /**
     * Factory method for stop loss evaluation strategy.
     *
     * @return new STOPLOSS mode strategy
     */
    public static PointsBasedExitStrategy forStopLoss() {
        return new PointsBasedExitStrategy(Mode.STOPLOSS);
    }

    @Override
    public int getPriority() {
        return mode == Mode.TARGET ? TARGET_PRIORITY : STOPLOSS_PRIORITY;
    }

    @Override
    public String getName() {
        return mode == Mode.TARGET ? "PointsBasedTarget" : "PointsBasedStopLoss";
    }

    @Override
    public ExitResult evaluate(ExitContext ctx) {
        final double cumulative = ctx.getCumulativePnL();

        if (mode == Mode.TARGET) {
            return evaluateTarget(ctx, cumulative);
        } else {
            return evaluateStopLoss(ctx, cumulative);
        }
    }

    /**
     * Evaluate cumulative target condition.
     */
    private ExitResult evaluateTarget(ExitContext ctx, double cumulative) {
        final double targetPts = ctx.getCumulativeTargetPoints();

        // Check target hit (profit)
        if (cumulative >= targetPts) {
            log.warn("Cumulative target hit for execution {}: cumulative={} points, target={} - Closing ALL legs",
                    ctx.getExecutionId(), formatDouble(cumulative), formatDouble(targetPts));
            return ExitResult.exitAll(buildExitReasonTarget(cumulative));
        }

        return ExitResult.noExit();
    }

    /**
     * Evaluate cumulative stop loss condition.
     */
    private ExitResult evaluateStopLoss(ExitContext ctx, double cumulative) {
        final double stopPts = ctx.getCumulativeStopPoints();
        // HFT: Pre-compute negation once
        final double negativeStopPts = -stopPts;

        // Check stoploss hit (loss)
        if (cumulative <= negativeStopPts) {
            log.warn("Cumulative stoploss hit for execution {}: cumulative={} points, stopLoss={} - Closing ALL legs",
                    ctx.getExecutionId(), formatDouble(cumulative), formatDouble(stopPts));
            return ExitResult.exitAll(buildExitReasonStoploss(cumulative));
        }

        return ExitResult.noExit();
    }

    // ==================== EXIT REASON BUILDERS ====================

    /**
     * Build target exit reason.
     */
    private String buildExitReasonTarget(double cumulative) {
        StringBuilder sb = getExitReasonBuilder();
        sb.append(EXIT_PREFIX_TARGET);
        appendDouble(sb, cumulative);
        sb.append(EXIT_SUFFIX_POINTS);
        return sb.toString();
    }

    /**
     * Build stoploss exit reason.
     */
    private String buildExitReasonStoploss(double cumulative) {
        StringBuilder sb = getExitReasonBuilder();
        sb.append(EXIT_PREFIX_STOPLOSS);
        appendDouble(sb, cumulative);
        sb.append(EXIT_SUFFIX_POINTS);
        return sb.toString();
    }
}

