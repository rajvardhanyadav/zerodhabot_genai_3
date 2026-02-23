package com.tradingbot.backtest.dto;

import com.tradingbot.model.StrategyType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Request DTO for running a single-day backtest.
 * Mirrors the strategy configuration used in live/paper trading.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestRequest {

    /** The specific trading day to backtest (must not be in the future). */
    @NotNull(message = "Backtest date is required")
    @PastOrPresent(message = "Backtest date must not be in the future")
    private LocalDate backtestDate;

    /** Strategy type to simulate. */
    @NotNull(message = "Strategy type is required")
    private StrategyType strategyType;

    /** Underlying index: NIFTY or BANKNIFTY. */
    @NotNull(message = "Instrument type is required")
    private String instrumentType;

    /** Expiry date for options contracts (format: yyyy-MM-dd). */
    @NotNull(message = "Expiry date is required")
    private String expiryDate;

    /** Number of lots to simulate (default: 1). */
    @Builder.Default
    private int lots = 1;

    // ==================== SL/TARGET CONFIGURATION ====================

    /** Exit mode: "points" or "premium". */
    private String slTargetMode;

    /** Stop loss in points (when mode = points). */
    private Double stopLossPoints;

    /** Target in points (when mode = points). */
    private Double targetPoints;

    /** Target decay % for premium-based exit. */
    private Double targetDecayPct;

    /** Stop loss expansion % for premium-based exit. */
    private Double stopLossExpansionPct;

    // ==================== TIME CONFIGURATION ====================

    /** Backtest start time in HH:mm (default: 09:20 — first 5-min candle). */
    @Builder.Default
    private String startTime = "09:20";

    /** Backtest end time in HH:mm (default: 15:30 — market close). */
    @Builder.Default
    private String endTime = "15:30";

    /** Auto square-off time in HH:mm (default: 15:10). */
    @Builder.Default
    private String autoSquareOffTime = "15:10";

    // ==================== CANDLE & RESTART CONFIGURATION ====================

    /** Candle interval for historical data (default: "minute" — smallest available). */
    @Builder.Default
    private String candleInterval = "minute";

    /** Enable auto-restart at next 5-min candle after SL/target hit. */
    @Builder.Default
    private boolean autoRestartEnabled = true;

    /** Max auto-restarts per day (0 = unlimited). */
    @Builder.Default
    private int maxAutoRestarts = 0;

    // ==================== TRAILING STOP CONFIGURATION ====================

    /** Enable trailing stop loss. */
    @Builder.Default
    private boolean trailingStopEnabled = false;

    /** Trailing stop activation threshold in points. */
    private Double trailingActivationPoints;

    /** Trailing stop distance in points. */
    private Double trailingDistancePoints;
}

