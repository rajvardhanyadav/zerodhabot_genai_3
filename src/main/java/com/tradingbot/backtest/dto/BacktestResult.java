package com.tradingbot.backtest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * Complete result of a single-day backtest execution.
 * Contains all trades, aggregate metrics, and execution metadata.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Complete result of a single-day backtest execution with trades and aggregate metrics")
public class BacktestResult {

    // ==================== IDENTIFICATION ====================

    @Schema(description = "Unique backtest execution identifier", example = "bt-a1b2c3d4")
    private String backtestId;

    @Schema(description = "The date that was backtested", example = "2026-03-10")
    private LocalDate backtestDate;

    @Schema(description = "Strategy type that was simulated", example = "SELL_ATM_STRADDLE")
    private String strategyType;

    @Schema(description = "Underlying instrument", example = "NIFTY")
    private String instrumentType;

    @Schema(description = "Backtest execution status")
    private BacktestStatus status;

    @Schema(description = "Error message if backtest failed", example = "No historical data available for the given date")
    private String errorMessage;

    // ==================== MARKET DATA ====================

    @Schema(description = "Spot price of the underlying at strategy entry", example = "21050.35")
    private double spotPriceAtEntry;

    @Schema(description = "ATM strike price selected", example = "21050.0")
    private double atmStrike;

    // ==================== TRADES ====================

    @Schema(description = "List of individual trades (entry→exit cycles) executed during the backtest")
    private List<BacktestTrade> trades;

    // ==================== AGGREGATE METRICS ====================

    @Schema(description = "Total P&L in points across all trades", example = "12.50")
    private double totalPnLPoints;

    @Schema(description = "Total P&L in INR across all trades", example = "625.00")
    private double totalPnLAmount;

    @Schema(description = "Total number of trades executed", example = "3")
    private int totalTrades;

    @Schema(description = "Number of profitable trades", example = "2")
    private int winningTrades;

    @Schema(description = "Number of losing trades", example = "1")
    private int losingTrades;

    @Schema(description = "Win rate as percentage (0-100)", example = "66.67")
    private double winRate;

    @Schema(description = "Maximum drawdown as percentage", example = "2.35")
    private double maxDrawdownPct;

    @Schema(description = "Maximum profit as percentage", example = "5.10")
    private double maxProfitPct;

    @Schema(description = "Average winning trade amount (INR)", example = "450.00")
    private double avgWinAmount;

    @Schema(description = "Average losing trade amount (INR)", example = "-200.00")
    private double avgLossAmount;

    @Schema(description = "Profit factor (gross profits / gross losses)", example = "2.25")
    private double profitFactor;

    @Schema(description = "Number of auto-restarts triggered", example = "2")
    private int restartCount;

    // ==================== EXECUTION METADATA ====================

    @Schema(description = "Backtest engine execution duration in milliseconds", example = "1450")
    private long executionDurationMs;

    /**
     * Status of a backtest execution.
     */
    @Schema(description = "Backtest execution status")
    public enum BacktestStatus {
        COMPLETED,
        FAILED,
        RUNNING
    }
}

