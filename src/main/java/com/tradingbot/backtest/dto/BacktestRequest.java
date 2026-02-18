package com.tradingbot.backtest.dto;

import com.tradingbot.model.StrategyType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Request DTO for backtesting a trading strategy over historical data.
 * This is completely decoupled from live trading to ensure isolation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BacktestRequest {

    /**
     * The specific day to run the backtest on.
     * Format: yyyy-MM-dd (e.g., "2025-12-15")
     */
    @NotNull(message = "Backtest date is required")
    private LocalDate backtestDate;

    /**
     * Strategy type to execute during the backtest.
     */
    @NotNull(message = "Strategy type is required")
    private StrategyType strategyType;

    /**
     * Underlying instrument (e.g., "NIFTY", "BANKNIFTY")
     */
    @NotNull(message = "Instrument type is required")
    private String instrumentType;

    /**
     * Expiry date for options contracts.
     * Format: yyyy-MM-dd (e.g., "2026-02-26" for Feb 26, 2026 expiry)
     * This is the actual expiry date of the options contract to be used in backtest.
     */
    @NotNull(message = "Expiry date is required")
    private LocalDate expiryDate;

    /**
     * Number of lots to simulate.
     * Defaults to 1 if not provided.
     */
    @Builder.Default
    private Integer lots = 1;

    /**
     * Stop loss in points (point-based mode).
     * Used when slTargetMode = "points"
     */
    private BigDecimal stopLossPoints;

    /**
     * Target profit in points (point-based mode).
     * Used when slTargetMode = "points"
     */
    private BigDecimal targetPoints;

    /**
     * Target decay percentage for premium-based exit.
     * Exit when combined LTP <= entryPremium * (1 - targetDecayPct).
     * Used when slTargetMode = "percentage"
     */
    private BigDecimal targetDecayPct;

    /**
     * Stop loss expansion percentage for premium-based exit.
     * Exit when combined LTP >= entryPremium * (1 + stopLossExpansionPct).
     * Used when slTargetMode = "percentage"
     */
    private BigDecimal stopLossExpansionPct;

    /**
     * SL/Target mode: "points" or "percentage"
     */
    @Builder.Default
    private String slTargetMode = "points";

    /**
     * Enable trailing stop loss.
     * Only applicable when slTargetMode = "points".
     */
    @Builder.Default
    private boolean trailingStopEnabled = false;

    /**
     * Trailing stop activation points.
     * Trailing begins when cumulative P&L >= activationPoints.
     */
    private BigDecimal trailingActivationPoints;

    /**
     * Trailing stop distance points.
     * Stop level = High Water Mark - distancePoints.
     */
    private BigDecimal trailingDistancePoints;

    /**
     * Time to start the backtest simulation (HH:mm format).
     * Defaults to market open time if not specified.
     */
    private String startTime;

    /**
     * Time to end the backtest simulation (HH:mm format).
     * Defaults to market close time if not specified.
     */
    private String endTime;

    /**
     * Candle interval for simulation (e.g., "minute", "5minute", "15minute").
     * Defaults to "minute" (minimum timeframe) for highest precision.
     */
    @Builder.Default
    private String candleInterval = "minute";

    /**
     * Enable fast-forward to the nearest candle start when strategy restart is triggered.
     * If true, aligns immediately with the start of the nearest 5-minute candle boundary.
     */
    @Builder.Default
    private boolean fastForwardEnabled = true;

    // ==================== HELPER METHODS ====================

    /**
     * Check if the backtest date is the expiry day.
     *
     * @return true if backtestDate equals expiryDate
     */
    public boolean isExpiryDay() {
        return expiryDate != null && backtestDate != null && expiryDate.equals(backtestDate);
    }

    /**
     * Get the expiry formatted for symbol generation.
     * Format: YYMMMDD (e.g., "26FEB26" for Feb 26, 2026)
     *
     * @return formatted expiry string for symbol construction
     */
    public String getExpiryForSymbol() {
        if (expiryDate == null) {
            return "";
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyMMMdd");
        return expiryDate.format(formatter).toUpperCase();
    }

    /**
     * Get the expiry formatted in short form for weekly expiries.
     * Format: YYMD or YYMdd (e.g., "26226" for Feb 26, 2026 if weekly)
     * NSE weekly format: YYMdd where M = 1-9,O,N,D for months
     *
     * @return formatted expiry string in NSE weekly format
     */
    public String getWeeklyExpiryForSymbol() {
        if (expiryDate == null) {
            return "";
        }
        int year = expiryDate.getYear() % 100;
        int month = expiryDate.getMonthValue();
        int day = expiryDate.getDayOfMonth();

        // Month encoding: 1-9 for Jan-Sep, O for Oct, N for Nov, D for Dec
        String monthChar;
        if (month <= 9) {
            monthChar = String.valueOf(month);
        } else {
            monthChar = switch (month) {
                case 10 -> "O";
                case 11 -> "N";
                case 12 -> "D";
                default -> String.valueOf(month);
            };
        }

        return String.format("%02d%s%02d", year, monthChar, day);
    }

    /**
     * Get expiry string for symbol generation, using appropriate format.
     * Uses weekly format for weekly expiries, monthly format for monthly.
     *
     * @param useWeeklyFormat true to use weekly format (YYMdd), false for monthly (YYMMMDD)
     * @return formatted expiry string
     */
    public String getFormattedExpiry(boolean useWeeklyFormat) {
        return useWeeklyFormat ? getWeeklyExpiryForSymbol() : getExpiryForSymbol();
    }

    /**
     * Validate that the expiry date is appropriate for the backtest date.
     *
     * @throws IllegalArgumentException if expiry is before backtest date
     */
    public void validateExpiry() {
        if (expiryDate == null) {
            throw new IllegalArgumentException("Expiry date is required");
        }
        if (backtestDate != null && expiryDate.isBefore(backtestDate)) {
            throw new IllegalArgumentException("Expiry date cannot be before backtest date");
        }
    }

    /**
     * Get expiry as String for backward compatibility.
     * @return expiry date as ISO string (yyyy-MM-dd)
     */
    public String getExpiry() {
        return expiryDate != null ? expiryDate.toString() : null;
    }
}
