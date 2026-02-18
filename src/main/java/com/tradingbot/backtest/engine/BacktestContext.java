package com.tradingbot.backtest.engine;

import com.tradingbot.backtest.dto.BacktestResult.BacktestTrade;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Context object that maintains state throughout a backtest execution.
 *
 * This is a mutable object passed to strategy methods to track:
 * - Current positions (simulated)
 * - Trade history
 * - P&L metrics
 * - Restart state
 *
 * All monetary values use BigDecimal for HFT-grade precision.
 */
@Data
public class BacktestContext {

    /**
     * Unique identifier for this backtest run.
     */
    private String backtestId;

    /**
     * Current simulation timestamp.
     */
    private LocalDateTime currentTime;

    // ==================== POSITION STATE ====================

    /**
     * Currently open positions keyed by trading symbol.
     */
    private Map<String, SimulatedPosition> openPositions = new HashMap<>();

    /**
     * Combined entry premium for straddle positions.
     */
    private BigDecimal entryPremium = BigDecimal.ZERO;

    /**
     * Strike price for current position (for straddles, this is the ATM strike).
     */
    private BigDecimal currentStrike = BigDecimal.ZERO;

    /**
     * Flag indicating if there's an active position.
     */
    private boolean positionActive = false;

    // ==================== P&L TRACKING ====================

    /**
     * Running total P&L in points.
     */
    private BigDecimal totalPnLPoints = BigDecimal.ZERO;

    /**
     * Running total P&L in INR.
     */
    private BigDecimal totalPnLAmount = BigDecimal.ZERO;

    /**
     * Peak equity observed (for drawdown calculation).
     */
    private BigDecimal peakEquity = BigDecimal.ZERO;

    /**
     * Maximum drawdown percentage observed.
     */
    private BigDecimal maxDrawdownPct = BigDecimal.ZERO;

    // ==================== TRADE HISTORY ====================

    /**
     * List of all trades executed during the backtest.
     */
    private List<BacktestTrade> trades = new ArrayList<>();

    /**
     * Trade counter.
     */
    private int tradeCount = 0;

    // ==================== RESTART STATE ====================

    /**
     * Number of restarts triggered during this session.
     */
    private int restartCount = 0;

    /**
     * Flag to indicate restart was requested.
     */
    private boolean restartRequested = false;

    /**
     * Timestamp when restart was triggered (for fast-forward calculation).
     */
    private LocalDateTime restartTriggeredAt;

    // ==================== CONFIGURATION ====================

    /**
     * Stop loss in points (for point-based mode).
     */
    private BigDecimal stopLossPoints;

    /**
     * Target in points (for point-based mode).
     */
    private BigDecimal targetPoints;

    /**
     * Target decay percentage (for premium-based mode).
     */
    private BigDecimal targetDecayPct;

    /**
     * Stop loss expansion percentage (for premium-based mode).
     */
    private BigDecimal stopLossExpansionPct;

    /**
     * SL/Target mode: "points" or "percentage".
     */
    private String slTargetMode = "points";

    /**
     * Lot size for the instrument.
     */
    private int lotSize = 1;

    /**
     * Number of lots being traded.
     */
    private int lots = 1;

    /**
     * Instrument type (e.g., "NIFTY", "BANKNIFTY").
     */
    private String instrumentType;

    /**
     * Expiry date for options contracts.
     */
    private LocalDate expiryDate;

    /**
     * Backtest date.
     */
    private LocalDate backtestDate;

    /**
     * Formatted expiry string for symbol generation.
     */
    private String expiryForSymbol;

    // ==================== CHARGES TRACKING ====================

    /**
     * Entry charges (brokerage, STT, etc.) for the current position.
     */
    private BigDecimal entryCharges = BigDecimal.ZERO;

    /**
     * Exit charges for the current position.
     */
    private BigDecimal exitCharges = BigDecimal.ZERO;

    /**
     * Cumulative charges across all trades.
     */
    private BigDecimal totalCharges = BigDecimal.ZERO;

    // ==================== HELPER METHODS ====================

    /**
     * Add a simulated position.
     */
    public void addPosition(String symbol, SimulatedPosition position) {
        openPositions.put(symbol, position);
        positionActive = true;
    }

    /**
     * Remove a position and record the trade.
     */
    public BacktestTrade closePosition(String symbol, BigDecimal exitPrice,
                                        LocalDateTime exitTime, String exitReason) {
        SimulatedPosition position = openPositions.remove(symbol);
        if (position == null) {
            return null;
        }

        tradeCount++;
        BigDecimal pnl = calculatePnL(position, exitPrice);

        BacktestTrade trade = BacktestTrade.builder()
                .tradeNumber(tradeCount)
                .tradingSymbol(symbol)
                .optionType(position.getOptionType())
                .strikePrice(position.getStrikePrice())
                .entryTime(position.getEntryTime())
                .entryPrice(position.getEntryPrice())
                .exitTime(exitTime)
                .exitPrice(exitPrice)
                .quantity(position.getQuantity())
                .transactionType(position.getTransactionType())
                .pnlPoints(pnl)
                .pnlAmount(pnl.multiply(BigDecimal.valueOf(position.getQuantity())))
                .exitReason(exitReason)
                .wasRestarted(restartRequested)
                .build();

        trades.add(trade);
        totalPnLPoints = totalPnLPoints.add(pnl);
        totalPnLAmount = totalPnLAmount.add(trade.getPnlAmount());

        // Update drawdown tracking
        updateEquityTracking();

        if (openPositions.isEmpty()) {
            positionActive = false;
        }

        return trade;
    }

    /**
     * Close all open positions.
     * Deducts entry and exit charges from P&L (same as paper/live trading).
     */
    public List<BacktestTrade> closeAllPositions(Map<String, BigDecimal> exitPrices,
                                                  LocalDateTime exitTime, String exitReason) {
        List<BacktestTrade> closedTrades = new ArrayList<>();
        List<String> symbols = new ArrayList<>(openPositions.keySet());

        for (String symbol : symbols) {
            BigDecimal exitPrice = exitPrices.getOrDefault(symbol, BigDecimal.ZERO);
            BacktestTrade trade = closePosition(symbol, exitPrice, exitTime, exitReason);
            if (trade != null) {
                closedTrades.add(trade);
            }
        }

        // Deduct charges from total P&L (same as paper/live trading)
        BigDecimal tradeCharges = entryCharges.add(exitCharges);
        totalPnLAmount = totalPnLAmount.subtract(tradeCharges);
        totalCharges = totalCharges.add(tradeCharges);

        // Reset charges for next trade
        entryCharges = BigDecimal.ZERO;
        exitCharges = BigDecimal.ZERO;

        return closedTrades;
    }

    /**
     * Calculate P&L for a position.
     */
    private BigDecimal calculatePnL(SimulatedPosition position, BigDecimal exitPrice) {
        BigDecimal diff = exitPrice.subtract(position.getEntryPrice());
        // For SELL positions, profit is when price goes down
        if ("SELL".equalsIgnoreCase(position.getTransactionType())) {
            diff = diff.negate();
        }
        return diff;
    }

    /**
     * Update peak equity and drawdown metrics.
     */
    private void updateEquityTracking() {
        if (totalPnLAmount.compareTo(peakEquity) > 0) {
            peakEquity = totalPnLAmount;
        } else if (peakEquity.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal drawdown = peakEquity.subtract(totalPnLAmount)
                    .divide(peakEquity, 4, java.math.RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            if (drawdown.compareTo(maxDrawdownPct) > 0) {
                maxDrawdownPct = drawdown;
            }
        }
    }

    /**
     * Request a strategy restart.
     */
    public void requestRestart() {
        this.restartRequested = true;
        this.restartTriggeredAt = currentTime;
        this.restartCount++;
    }

    /**
     * Clear restart flag after restart is processed.
     */
    public void clearRestartFlag() {
        this.restartRequested = false;
        this.restartTriggeredAt = null;
    }

    /**
     * Represents a simulated position during backtesting.
     */
    @Data
    public static class SimulatedPosition {
        private String tradingSymbol;
        private String optionType; // CE, PE
        private BigDecimal strikePrice;
        private LocalDateTime entryTime;
        private BigDecimal entryPrice;
        private int quantity;
        private String transactionType; // BUY, SELL
        private long instrumentToken;
    }
}

