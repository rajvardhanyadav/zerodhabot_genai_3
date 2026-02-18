package com.tradingbot.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Result DTO containing backtesting execution results and performance metrics.
 * Provides detailed trade-by-trade breakdown and aggregated statistics.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BacktestResult {

    /**
     * Unique identifier for this backtest execution.
     */
    private String backtestId;

    /**
     * Date on which the backtest was run.
     */
    private LocalDate backtestDate;

    /**
     * Strategy type that was backtested.
     */
    private String strategyType;

    /**
     * Instrument type (e.g., NIFTY, BANKNIFTY).
     */
    private String instrumentType;

    /**
     * Status of the backtest: COMPLETED, FAILED, PARTIAL
     */
    private BacktestStatus status;

    /**
     * Individual trades executed during the backtest.
     */
    private List<BacktestTrade> trades;

    /**
     * Total realized profit/loss in points.
     */
    private BigDecimal totalPnLPoints;

    /**
     * Total realized profit/loss in INR (simulated).
     */
    private BigDecimal totalPnLAmount;

    /**
     * Total charges (brokerage, STT, etc.) deducted from P&L.
     */
    private BigDecimal totalCharges;

    /**
     * Total number of trades executed.
     */
    private int totalTrades;

    /**
     * Number of winning trades.
     */
    private int winningTrades;

    /**
     * Number of losing trades.
     */
    private int losingTrades;

    /**
     * Win rate as a percentage (0-100).
     */
    private BigDecimal winRate;

    /**
     * Maximum drawdown observed during the backtest (in percentage).
     */
    private BigDecimal maxDrawdownPct;

    /**
     * Maximum profit observed during the backtest (in percentage).
     */
    private BigDecimal maxProfitPct;

    /**
     * Average profit per winning trade.
     */
    private BigDecimal avgWinAmount;

    /**
     * Average loss per losing trade.
     */
    private BigDecimal avgLossAmount;

    /**
     * Profit factor = (Total profits) / (Total losses).
     * A value > 1 indicates a profitable strategy.
     */
    private BigDecimal profitFactor;

    /**
     * Number of strategy restarts triggered during the session.
     */
    private int restartCount;

    /**
     * Timestamp when backtest execution started.
     */
    private LocalDateTime executionStartTime;

    /**
     * Timestamp when backtest execution completed.
     */
    private LocalDateTime executionEndTime;

    /**
     * Total wall-clock time taken to run the backtest (milliseconds).
     */
    private long executionDurationMs;

    /**
     * Error message if the backtest failed.
     */
    private String errorMessage;

    /**
     * Enum representing the status of a backtest.
     */
    public enum BacktestStatus {
        COMPLETED,
        FAILED,
        PARTIAL,
        RUNNING
    }

    /**
     * Represents a single simulated trade during backtesting.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BacktestTrade {
        private int tradeNumber;
        private String tradingSymbol;
        private String optionType; // CE, PE
        private BigDecimal strikePrice;
        private LocalDateTime entryTime;
        private BigDecimal entryPrice;
        private LocalDateTime exitTime;
        private BigDecimal exitPrice;
        private int quantity;
        private String transactionType; // BUY, SELL
        private BigDecimal pnlPoints;
        private BigDecimal pnlAmount;
        private String exitReason; // TARGET_HIT, STOPLOSS_HIT, SQUARE_OFF, RESTART
        private boolean wasRestarted;
    }
}

