package com.tradingbot.backtest.dto;

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
public class BacktestResult {

    // ==================== IDENTIFICATION ====================

    private String backtestId;
    private LocalDate backtestDate;
    private String strategyType;
    private String instrumentType;
    private BacktestStatus status;
    private String errorMessage;

    // ==================== MARKET DATA ====================

    private double spotPriceAtEntry;
    private double atmStrike;

    // ==================== TRADES ====================

    private List<BacktestTrade> trades;

    // ==================== AGGREGATE METRICS ====================

    private double totalPnLPoints;
    private double totalPnLAmount;
    private int totalTrades;
    private int winningTrades;
    private int losingTrades;
    private double winRate;
    private double maxDrawdownPct;
    private double maxProfitPct;
    private double avgWinAmount;
    private double avgLossAmount;
    private double profitFactor;
    private int restartCount;

    // ==================== EXECUTION METADATA ====================

    private long executionDurationMs;

    /**
     * Status of a backtest execution.
     */
    public enum BacktestStatus {
        COMPLETED,
        FAILED,
        RUNNING
    }
}

