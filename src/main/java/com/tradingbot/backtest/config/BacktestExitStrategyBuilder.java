package com.tradingbot.backtest.config;

import com.tradingbot.backtest.dto.BacktestRequest;
import com.tradingbot.service.strategy.monitoring.exit.*;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the same exit strategy chain for backtest as used in live trading.
 * <p>
 * This builder ensures that backtest uses IDENTICAL exit strategy configurations
 * as live/paper trading, guaranteeing consistent behavior across all trading modes.
 *
 * <h2>Strategy Order (by priority)</h2>
 * <ol>
 *   <li>TimeBasedForcedExitStrategy (priority 0) - Market close forced exit</li>
 *   <li>PremiumBasedExitStrategy (priority 50) - Percentage-based exits</li>
 *   <li>PointsBasedExitStrategy for target (priority 100) - Fixed point target</li>
 *   <li>TrailingStopLossStrategy (priority 300) - Dynamic trailing stop</li>
 *   <li>PointsBasedExitStrategy for stop loss (priority 400) - Fixed point SL</li>
 * </ol>
 *
 * @see ExitStrategy
 * @see BacktestRequest
 */
@Slf4j
public class BacktestExitStrategyBuilder {

    /** Default forced exit time (15:10 IST) */
    private static final LocalTime DEFAULT_FORCED_EXIT_TIME = LocalTime.of(15, 10);

    /** Default target points */
    private static final double DEFAULT_TARGET_POINTS = 2.5;

    /** Default stop loss points */
    private static final double DEFAULT_STOP_LOSS_POINTS = 4.0;

    /** Default target decay percentage */
    private static final double DEFAULT_TARGET_DECAY_PCT = 5.0;

    /** Default stop loss expansion percentage */
    private static final double DEFAULT_SL_EXPANSION_PCT = 10.0;

    private final BacktestRequest request;

    // Configuration flags
    private boolean enableTimeBasedExit = true;
    private boolean enableTrailingStop = false;
    private double trailingActivationPoints = 1.0;
    private double trailingDistancePoints = 0.5;
    private LocalTime forcedExitTime = DEFAULT_FORCED_EXIT_TIME;

    /**
     * Creates a builder for the given backtest request.
     *
     * @param request the BacktestRequest containing strategy parameters
     */
    public BacktestExitStrategyBuilder(BacktestRequest request) {
        this.request = request;
    }

    /**
     * Enables time-based forced exit at specified time.
     *
     * @param exitTime the forced exit time (default 15:10 IST)
     * @return this builder for chaining
     */
    public BacktestExitStrategyBuilder withTimeBasedExit(LocalTime exitTime) {
        this.enableTimeBasedExit = true;
        this.forcedExitTime = exitTime != null ? exitTime : DEFAULT_FORCED_EXIT_TIME;
        return this;
    }

    /**
     * Disables time-based forced exit.
     *
     * @return this builder for chaining
     */
    public BacktestExitStrategyBuilder withoutTimeBasedExit() {
        this.enableTimeBasedExit = false;
        return this;
    }

    /**
     * Enables trailing stop loss with specified parameters.
     *
     * @param activationPoints P&L threshold to activate trailing
     * @param distancePoints distance trailing stop follows behind HWM
     * @return this builder for chaining
     */
    public BacktestExitStrategyBuilder withTrailingStop(double activationPoints, double distancePoints) {
        this.enableTrailingStop = true;
        this.trailingActivationPoints = activationPoints > 0 ? activationPoints : 1.0;
        this.trailingDistancePoints = distancePoints > 0 ? distancePoints : 0.5;
        return this;
    }

    /**
     * Builds the list of exit strategies based on request configuration.
     *
     * @return list of ExitStrategy sorted by priority
     */
    public List<ExitStrategy> build() {
        List<ExitStrategy> strategies = new ArrayList<>();

        // 1. Time-based forced exit (highest priority)
        if (enableTimeBasedExit) {
            TimeBasedForcedExitStrategy timeStrategy = new TimeBasedForcedExitStrategy(forcedExitTime);
            strategies.add(timeStrategy);
            log.debug("Added TimeBasedForcedExitStrategy: cutoff={}", forcedExitTime);
        }

        // 2. Premium-based or Points-based exit based on mode
        String slTargetMode = request.getSlTargetMode();
        boolean isPremiumMode = "percentage".equalsIgnoreCase(slTargetMode);

        if (isPremiumMode) {
            // Premium-based exit (percentage mode)
            PremiumBasedExitStrategy premiumStrategy = new PremiumBasedExitStrategy();
            strategies.add(premiumStrategy);
            log.debug("Added PremiumBasedExitStrategy: targetDecay={}%, slExpansion={}%",
                request.getTargetDecayPct(), request.getStopLossExpansionPct());
        } else {
            // Points-based exit
            double targetPoints = request.getTargetPoints() != null
                ? request.getTargetPoints().doubleValue()
                : DEFAULT_TARGET_POINTS;
            double stopLossPoints = request.getStopLossPoints() != null
                ? request.getStopLossPoints().doubleValue()
                : DEFAULT_STOP_LOSS_POINTS;

            // Target strategy
            strategies.add(PointsBasedExitStrategy.forTarget());
            log.debug("Added PointsBasedExitStrategy (TARGET): target={} pts", targetPoints);

            // Trailing stop (check both builder flag and request flag)
            boolean useTrailingStop = enableTrailingStop || request.isTrailingStopEnabled();
            if (useTrailingStop) {
                double actPts = trailingActivationPoints;
                double distPts = trailingDistancePoints;

                // Use request values if available
                if (request.getTrailingActivationPoints() != null) {
                    actPts = request.getTrailingActivationPoints().doubleValue();
                }
                if (request.getTrailingDistancePoints() != null) {
                    distPts = request.getTrailingDistancePoints().doubleValue();
                }

                TrailingStopLossStrategy trailingStrategy =
                    new TrailingStopLossStrategy(actPts, distPts);
                strategies.add(trailingStrategy);
                log.debug("Added TrailingStopLossStrategy: activation={} pts, distance={} pts",
                    actPts, distPts);
            }

            // Stop loss strategy
            strategies.add(PointsBasedExitStrategy.forStopLoss());
            log.debug("Added PointsBasedExitStrategy (STOPLOSS): SL={} pts", stopLossPoints);
        }

        // Sort by priority
        strategies.sort(Comparator.comparingInt(ExitStrategy::getPriority));

        log.info("Built {} exit strategies for backtest (mode={})",
            strategies.size(), slTargetMode);

        return strategies;
    }

    /**
     * Static factory method to build default strategies for a request.
     *
     * @param request the BacktestRequest
     * @return list of ExitStrategy
     */
    public static List<ExitStrategy> buildDefault(BacktestRequest request) {
        return new BacktestExitStrategyBuilder(request).build();
    }

    /**
     * Static factory method to build strategies without time-based exit.
     * <p>
     * Useful when market close is handled separately by the backtest engine.
     *
     * @param request the BacktestRequest
     * @return list of ExitStrategy
     */
    public static List<ExitStrategy> buildWithoutTimeBased(BacktestRequest request) {
        return new BacktestExitStrategyBuilder(request)
            .withoutTimeBasedExit()
            .build();
    }
}


