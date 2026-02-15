/**
 * HFT-optimized exit strategies for position monitoring.
 * <p>
 * This package contains the Strategy pattern implementation for evaluating
 * exit conditions in high-frequency trading (HFT) options strategies.
 *
 * <h2>Architecture</h2>
 * <ul>
 *   <li>{@link com.tradingbot.service.strategy.monitoring.exit.ExitStrategy} - Base interface for all strategies</li>
 *   <li>{@link com.tradingbot.service.strategy.monitoring.exit.ExitContext} - Context holder for evaluation state</li>
 *   <li>{@link com.tradingbot.service.strategy.monitoring.exit.ExitResult} - Result object for exit decisions</li>
 * </ul>
 *
 * <h2>Strategy Implementations</h2>
 * <ul>
 *   <li>{@link com.tradingbot.service.strategy.monitoring.exit.TimeBasedForcedExitStrategy} - Time-based forced exit (priority 0)</li>
 *   <li>{@link com.tradingbot.service.strategy.monitoring.exit.PremiumBasedExitStrategy} - Premium decay/expansion (priority 50)</li>
 *   <li>{@link com.tradingbot.service.strategy.monitoring.exit.PointsBasedExitStrategy} - Fixed-point MTM (priority 100/400)</li>
 *   <li>{@link com.tradingbot.service.strategy.monitoring.exit.TrailingStopLossStrategy} - Dynamic trailing stop (priority 300)</li>
 * </ul>
 *
 * <h2>Evaluation Order</h2>
 * Strategies are evaluated in priority order (lower = first):
 * <ol>
 *   <li>Time-Based Forced Exit (0) - Highest priority, bypasses all P&L checks</li>
 *   <li>Premium-Based Exit (50) - For premium mode strategies</li>
 *   <li>Points Target (100) - Cumulative target hit</li>
 *   <li>Trailing Stop (300) - Dynamic trailing after activation</li>
 *   <li>Points Stop Loss (400) - Fixed cumulative stop loss</li>
 * </ol>
 *
 * <h2>HFT Optimizations</h2>
 * <ul>
 *   <li>Zero allocation on hot path (pre-allocated NO_EXIT_RESULT)</li>
 *   <li>ThreadLocal StringBuilders for exit reason construction</li>
 *   <li>Primitive arithmetic (no BigDecimal)</li>
 *   <li>Pre-computed thresholds passed via context</li>
 * </ul>
 *
 * @see com.tradingbot.service.strategy.monitoring.PositionMonitor
 */
package com.tradingbot.service.strategy.monitoring.exit;

