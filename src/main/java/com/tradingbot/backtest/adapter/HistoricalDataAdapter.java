package com.tradingbot.backtest.adapter;

import com.tradingbot.backtest.dto.CandleData;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Adapter interface for fetching historical market data.
 *
 * This interface abstracts the data source allowing easy swapping between:
 * - Kite Historical API (production)
 * - Local CSV/database files (testing)
 * - Mock data (unit testing)
 *
 * Implementations must be stateless and thread-safe.
 */
public interface HistoricalDataAdapter {

    /**
     * Fetch historical candle data for a specific instrument and date range.
     *
     * @param instrumentToken Kite instrument token (e.g., "256265" for NIFTY 50)
     * @param fromDate Start date for historical data (inclusive)
     * @param toDate End date for historical data (inclusive)
     * @param interval Candle interval (e.g., "minute", "5minute", "15minute", "day")
     * @return List of CandleData sorted by timestamp ascending
     * @throws HistoricalDataException if data fetch fails
     */
    List<CandleData> fetchCandles(
            String instrumentToken,
            LocalDate fromDate,
            LocalDate toDate,
            String interval
    ) throws HistoricalDataException;

    /**
     * Fetch historical candle data for a specific time range within a day.
     *
     * @param instrumentToken Kite instrument token
     * @param fromDateTime Start datetime (inclusive)
     * @param toDateTime End datetime (inclusive)
     * @param interval Candle interval
     * @return List of CandleData sorted by timestamp ascending
     * @throws HistoricalDataException if data fetch fails
     */
    List<CandleData> fetchCandles(
            String instrumentToken,
            LocalDateTime fromDateTime,
            LocalDateTime toDateTime,
            String interval
    ) throws HistoricalDataException;

    /**
     * Fetch historical candles for a specific trading symbol on a given date.
     *
     * @param tradingSymbol The trading symbol (e.g., "NIFTY25FEB26500CE")
     * @param exchange Exchange (e.g., "NFO", "NSE")
     * @param date The specific date to fetch data for
     * @param interval Candle interval
     * @return List of CandleData sorted by timestamp ascending
     * @throws HistoricalDataException if data fetch fails
     */
    List<CandleData> fetchCandlesBySymbol(
            String tradingSymbol,
            String exchange,
            LocalDate date,
            String interval
    ) throws HistoricalDataException;

    /**
     * Fetch spot price candles for an index (e.g., NIFTY 50, NIFTY BANK).
     *
     * @param indexName Index name ("NIFTY" or "BANKNIFTY")
     * @param date The specific date to fetch data for
     * @param interval Candle interval
     * @return List of CandleData sorted by timestamp ascending
     * @throws HistoricalDataException if data fetch fails
     */
    List<CandleData> fetchIndexCandles(
            String indexName,
            LocalDate date,
            String interval
    ) throws HistoricalDataException;

    /**
     * Fetch option candles for a specific strike, option type, and expiry.
     * This is the primary method for fetching option chain data for backtesting.
     *
     * @param instrumentType Underlying instrument (e.g., "NIFTY", "BANKNIFTY")
     * @param strike Strike price
     * @param optionType Option type ("CE" or "PE")
     * @param expiryDate Expiry date of the option contract
     * @param date The specific date to fetch data for
     * @param interval Candle interval (recommended: "minute" for highest precision)
     * @return List of CandleData sorted by timestamp ascending
     * @throws HistoricalDataException if data fetch fails or option not found
     */
    List<CandleData> fetchOptionCandles(
            String instrumentType,
            BigDecimal strike,
            String optionType,
            LocalDate expiryDate,
            LocalDate date,
            String interval
    ) throws HistoricalDataException;

    /**
     * Generate trading symbol for an option contract.
     *
     * @param instrumentType Underlying instrument (e.g., "NIFTY", "BANKNIFTY")
     * @param strike Strike price
     * @param optionType Option type ("CE" or "PE")
     * @param expiryDate Expiry date of the option contract
     * @return Trading symbol (e.g., "NIFTY2622625500CE" for NIFTY 25500CE expiring Feb 26, 2026)
     */
    String generateOptionSymbol(
            String instrumentType,
            BigDecimal strike,
            String optionType,
            LocalDate expiryDate
    );

    /**
     * Resolve instrument token for a given trading symbol.
     *
     * @param tradingSymbol Trading symbol
     * @param exchange Exchange
     * @return Instrument token as string
     * @throws HistoricalDataException if resolution fails
     */
    String resolveInstrumentToken(String tradingSymbol, String exchange)
            throws HistoricalDataException;

    /**
     * Check if historical data is available for the given date.
     *
     * @param date Date to check
     * @return true if data is available (market was open on that day)
     */
    boolean isDataAvailable(LocalDate date);

    /**
     * Custom exception for historical data fetch failures.
     */
    class HistoricalDataException extends RuntimeException {
        public HistoricalDataException(String message) {
            super(message);
        }

        public HistoricalDataException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

