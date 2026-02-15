package com.tradingbot.service.strategy.monitoring.exit;

import lombok.Getter;

/**
 * HFT-optimized result holder for exit strategy evaluation.
 * <p>
 * Represents the outcome of an {@link ExitStrategy#evaluate(ExitContext)} call.
 * Uses an enum-based type system for fast branching in the hot path.
 *
 * <h2>Result Types</h2>
 * <ul>
 *   <li>{@link ExitType#NO_EXIT} - No exit condition met, continue monitoring</li>
 *   <li>{@link ExitType#EXIT_ALL} - Exit all legs immediately</li>
 *   <li>{@link ExitType#EXIT_LEG} - Exit specific leg only (individual leg exit)</li>
 *   <li>{@link ExitType#ADJUST_LEG} - Exit profitable leg and request replacement</li>
 * </ul>
 *
 * <h2>HFT Optimizations</h2>
 * <ul>
 *   <li>Pre-allocated singleton for NO_EXIT (zero allocation for common case)</li>
 *   <li>Enum-based type for fast switch dispatch</li>
 *   <li>Immutable for thread-safe sharing</li>
 * </ul>
 */
@Getter
public final class ExitResult {

    /**
     * Exit result types for fast switch-based dispatch.
     */
    public enum ExitType {
        /** No exit condition met */
        NO_EXIT,
        /** Exit all legs (cumulative target/SL/trailing stop) */
        EXIT_ALL,
        /** Exit specific leg only (individual leg stop loss) */
        EXIT_LEG,
        /** Exit leg and request replacement (premium-based adjustment) */
        ADJUST_LEG
    }

    /** Pre-allocated singleton for NO_EXIT (zero allocation on hot path) */
    public static final ExitResult NO_EXIT_RESULT = new ExitResult(ExitType.NO_EXIT, null, null, null, 0.0, null);

    /** Exit type determining the action to take */
    private final ExitType exitType;

    /** Exit reason string (formatted by strategy) */
    private final String exitReason;

    /** Symbol of leg to exit (for EXIT_LEG and ADJUST_LEG types) */
    private final String legSymbol;

    /** Type of new leg to add (for ADJUST_LEG type) */
    private final String newLegType;

    /** Target premium for new leg (for ADJUST_LEG type) */
    private final double targetPremiumForNewLeg;

    /** Symbol of loss-making leg for reference (for ADJUST_LEG type) */
    private final String lossMakingLegSymbol;

    /**
     * Private constructor - use factory methods for construction.
     */
    private ExitResult(ExitType exitType, String exitReason, String legSymbol,
                       String newLegType, double targetPremiumForNewLeg, String lossMakingLegSymbol) {
        this.exitType = exitType;
        this.exitReason = exitReason;
        this.legSymbol = legSymbol;
        this.newLegType = newLegType;
        this.targetPremiumForNewLeg = targetPremiumForNewLeg;
        this.lossMakingLegSymbol = lossMakingLegSymbol;
    }

    /**
     * Factory method for NO_EXIT result (uses pre-allocated singleton).
     *
     * @return singleton NO_EXIT result
     */
    public static ExitResult noExit() {
        return NO_EXIT_RESULT;
    }

    /**
     * Factory method for EXIT_ALL result.
     *
     * @param exitReason formatted exit reason string
     * @return new EXIT_ALL result
     */
    public static ExitResult exitAll(String exitReason) {
        return new ExitResult(ExitType.EXIT_ALL, exitReason, null, null, 0.0, null);
    }

    /**
     * Factory method for EXIT_LEG result.
     *
     * @param exitReason formatted exit reason string
     * @param legSymbol symbol of the leg to exit
     * @return new EXIT_LEG result
     */
    public static ExitResult exitLeg(String exitReason, String legSymbol) {
        return new ExitResult(ExitType.EXIT_LEG, exitReason, legSymbol, null, 0.0, null);
    }

    /**
     * Factory method for ADJUST_LEG result (exit and replace).
     *
     * @param exitReason formatted exit reason string
     * @param legSymbol symbol of the leg to exit
     * @param newLegType type of new leg to add (CE or PE)
     * @param targetPremiumForNewLeg target premium for the new leg
     * @param lossMakingLegSymbol symbol of the loss-making leg for reference
     * @return new ADJUST_LEG result
     */
    public static ExitResult adjustLeg(String exitReason, String legSymbol,
                                        String newLegType, double targetPremiumForNewLeg,
                                        String lossMakingLegSymbol) {
        return new ExitResult(ExitType.ADJUST_LEG, exitReason, legSymbol,
                newLegType, targetPremiumForNewLeg, lossMakingLegSymbol);
    }

    /**
     * HFT: Fast check if this result requires action.
     *
     * @return true if any exit action is required
     */
    public boolean requiresAction() {
        return exitType != ExitType.NO_EXIT;
    }

    /**
     * HFT: Check if this is an exit-all-legs result.
     *
     * @return true if EXIT_ALL type
     */
    public boolean isExitAll() {
        return exitType == ExitType.EXIT_ALL;
    }

    /**
     * HFT: Check if this is an individual leg exit result.
     *
     * @return true if EXIT_LEG type
     */
    public boolean isExitLeg() {
        return exitType == ExitType.EXIT_LEG;
    }

    /**
     * HFT: Check if this is a leg adjustment result.
     *
     * @return true if ADJUST_LEG type
     */
    public boolean isAdjustLeg() {
        return exitType == ExitType.ADJUST_LEG;
    }
}

