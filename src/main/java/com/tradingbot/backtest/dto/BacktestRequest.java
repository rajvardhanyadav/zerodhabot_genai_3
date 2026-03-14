package com.tradingbot.backtest.dto;

import com.tradingbot.model.StrategyType;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Request to run a single-day strategy backtest against historical market data")
public class BacktestRequest {

    @NotNull(message = "Backtest date is required")
    @PastOrPresent(message = "Backtest date must not be in the future")
    @Schema(description = "The specific trading day to backtest (must not be in the future)", example = "2026-03-10", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate backtestDate;

    @NotNull(message = "Strategy type is required")
    @Schema(description = "Strategy type to simulate", example = "SELL_ATM_STRADDLE", requiredMode = Schema.RequiredMode.REQUIRED)
    private StrategyType strategyType;

    @NotNull(message = "Instrument type is required")
    @Schema(description = "Underlying index: NIFTY or BANKNIFTY", example = "NIFTY", requiredMode = Schema.RequiredMode.REQUIRED)
    private String instrumentType;

    @NotNull(message = "Expiry date is required")
    @Schema(description = "Expiry date for options contracts (yyyy-MM-dd)", example = "2026-03-19", requiredMode = Schema.RequiredMode.REQUIRED)
    private String expiryDate;

    @Builder.Default
    @Schema(description = "Number of lots to simulate (default: 1)", example = "1")
    private int lots = 1;

    // ==================== SL/TARGET CONFIGURATION ====================

    @Schema(description = "Exit mode: 'points' or 'premium'", example = "points")
    private String slTargetMode;

    @Schema(description = "Stop loss in points (when mode = points)", example = "50.0")
    private Double stopLossPoints;

    @Schema(description = "Target in points (when mode = points)", example = "50.0")
    private Double targetPoints;

    @Schema(description = "Target decay % for premium-based exit", example = "5.0")
    private Double targetDecayPct;

    @Schema(description = "Stop loss expansion % for premium-based exit", example = "10.0")
    private Double stopLossExpansionPct;

    // ==================== TIME CONFIGURATION ====================

    @Builder.Default
    @Schema(description = "Backtest start time in HH:mm (default: 09:20)", example = "09:20")
    private String startTime = "09:20";

    @Builder.Default
    @Schema(description = "Backtest end time in HH:mm (default: 15:30)", example = "15:30")
    private String endTime = "15:30";

    @Builder.Default
    @Schema(description = "Auto square-off time in HH:mm (default: 15:10)", example = "15:10")
    private String autoSquareOffTime = "15:10";

    // ==================== CANDLE & RESTART CONFIGURATION ====================

    @Builder.Default
    @Schema(description = "Candle interval for historical data (default: minute)", example = "minute")
    private String candleInterval = "minute";

    @Builder.Default
    @Schema(description = "Enable auto-restart at next 5-min candle after SL/target hit", example = "true")
    private boolean autoRestartEnabled = true;

    @Builder.Default
    @Schema(description = "Max auto-restarts per day (0 = unlimited)", example = "0")
    private int maxAutoRestarts = 0;

    // ==================== TRAILING STOP CONFIGURATION ====================

    @Builder.Default
    @Schema(description = "Enable trailing stop loss", example = "false")
    private boolean trailingStopEnabled = false;

    @Schema(description = "Trailing stop activation threshold in points", example = "30.0")
    private Double trailingActivationPoints;

    @Schema(description = "Trailing stop distance in points", example = "15.0")
    private Double trailingDistancePoints;
}

