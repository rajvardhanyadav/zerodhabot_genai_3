package com.tradingbot.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Represents a single candle (OHLC) data point.
 * Used by the backtest engine to simulate price movements.
 *
 * Note: All price fields use BigDecimal for HFT-grade decimal precision
 * to avoid floating-point arithmetic errors.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CandleData {

    /**
     * Timestamp of the candle start.
     */
    private LocalDateTime timestamp;

    /**
     * Opening price of the candle.
     */
    private BigDecimal open;

    /**
     * Highest price during the candle period.
     */
    private BigDecimal high;

    /**
     * Lowest price during the candle period.
     */
    private BigDecimal low;

    /**
     * Closing price of the candle.
     */
    private BigDecimal close;

    /**
     * Trading volume during the candle period.
     */
    private long volume;

    /**
     * Open Interest (for derivatives).
     */
    private long openInterest;

    /**
     * Instrument token this candle belongs to.
     */
    private String instrumentToken;

    /**
     * Trading symbol (e.g., "NIFTY25FEB26500CE").
     */
    private String tradingSymbol;

    /**
     * Returns the typical price (HLC/3) for the candle.
     * Useful for indicator calculations.
     */
    public BigDecimal getTypicalPrice() {
        return high.add(low).add(close)
                .divide(BigDecimal.valueOf(3), 4, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Returns the candle body size (absolute difference between open and close).
     */
    public BigDecimal getBodySize() {
        return close.subtract(open).abs();
    }

    /**
     * Returns true if this is a bullish candle (close > open).
     */
    public boolean isBullish() {
        return close.compareTo(open) > 0;
    }

    /**
     * Returns true if this is a bearish candle (close < open).
     */
    public boolean isBearish() {
        return close.compareTo(open) < 0;
    }
}

