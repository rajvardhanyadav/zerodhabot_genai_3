package com.tradingbot.service.strategy.monitoring.exit;

/**
 * Strategy interface for HFT-optimized exit evaluation.
 * <p>
 * Implementations encapsulate specific exit logic (points-based, premium-based, time-based)
 * and are evaluated in priority order by {@link com.tradingbot.service.strategy.monitoring.PositionMonitor}.
 *
 * <h2>Design Principles</h2>
 * <ul>
 *   <li>Stateless evaluation - all state passed via {@link ExitContext}</li>
 *   <li>Zero allocation on hot path - return pre-allocated {@link ExitResult#NO_EXIT_RESULT} when no exit</li>
 *   <li>Single responsibility - each strategy handles one exit mechanism</li>
 *   <li>Priority-based ordering - lower priority number = evaluated first</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <ul>
 *   <li>Implementations should be thread-safe for concurrent evaluation</li>
 *   <li>Use ThreadLocal for any mutable state (e.g., StringBuilder for exit reasons)</li>
 *   <li>Configuration should be immutable (set at construction)</li>
 * </ul>
 *
 * <h2>HFT Optimizations</h2>
 * <ul>
 *   <li>Avoid object allocation in {@link #evaluate(ExitContext)} - use pre-allocated results</li>
 *   <li>Use primitive arithmetic - no BigDecimal or autoboxing</li>
 *   <li>Prefer indexed loops over iterators</li>
 *   <li>Cache computed values in context for reuse by other strategies</li>
 * </ul>
 *
 * @see ExitContext
 * @see ExitResult
 */
public interface ExitStrategy {

    /**
     * Strategy priority for evaluation order.
     * Lower values are evaluated first.
     * <p>
     * Recommended priority ranges:
     * <ul>
     *   <li>0-99: Forced exits (time-based, emergency)</li>
     *   <li>100-199: Primary exits (target, premium decay)</li>
     *   <li>200-299: Individual leg exits</li>
     *   <li>300-399: Trailing stops</li>
     *   <li>400-499: Fixed stop losses</li>
     * </ul>
     *
     * @return priority value (lower = higher priority)
     */
    int getPriority();

    /**
     * Evaluates exit conditions based on current market state.
     * <p>
     * This method is called on the <b>hot path</b> for every tick. Implementations
     * must be highly optimized:
     * <ul>
     *   <li>Return {@link ExitResult#noExit()} immediately if conditions not met</li>
     *   <li>Use pre-computed values from context (cumulativePnL, combinedLTP)</li>
     *   <li>Avoid string formatting unless exit is triggered</li>
     *   <li>Use primitive comparisons only</li>
     * </ul>
     *
     * @param ctx evaluation context containing legs, thresholds, and computed values
     * @return exit result (use {@link ExitResult#noExit()} for no action)
     */
    ExitResult evaluate(ExitContext ctx);

    /**
     * Human-readable name for logging and debugging.
     *
     * @return strategy name (e.g., "PointsBasedTarget", "PremiumDecay")
     */
    String getName();

    /**
     * Check if this strategy is enabled/applicable for the current context.
     * <p>
     * Default implementation returns true. Override to add conditional activation
     * (e.g., trailing stop only after profit threshold).
     *
     * @param ctx evaluation context
     * @return true if strategy should be evaluated
     */
    default boolean isEnabled(ExitContext ctx) {
        return true;
    }
}

