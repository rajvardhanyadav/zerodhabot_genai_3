package com.tradingbot.backtest.engine;

import com.tradingbot.backtest.config.BacktestConfig;
import com.tradingbot.service.TradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;

/**
 * Fetches historical candle data from Kite API for backtest simulation.
 * Handles rate limiting and error wrapping.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HistoricalDataFetcher {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final TradingService tradingService;
    private final BacktestConfig backtestConfig;

    /**
     * Fetch 1-minute (or configured interval) candle data for a full trading day.
     *
     * @param instrumentToken Kite instrument token (as String)
     * @param date            the trading day
     * @param interval        candle interval ("minute", "5minute", etc.)
     * @return HistoricalData containing the candle array
     * @throws BacktestException if the API call fails
     */
    public HistoricalData fetchDayCandles(String instrumentToken, LocalDate date, String interval) {
        // Build from/to covering the full trading session: 9:15 AM – 15:30 PM IST
        ZonedDateTime from = date.atTime(9, 15, 0).atZone(IST);
        ZonedDateTime to = date.atTime(15, 30, 0).atZone(IST);

        Date fromDate = Date.from(from.toInstant());
        Date toDate = Date.from(to.toInstant());

        log.info("Fetching historical data: token={}, date={}, interval={}", instrumentToken, date, interval);

        // Rate limiting: sleep before API call to respect Kite's 3 req/sec limit
        sleepForRateLimit();

        try {
            HistoricalData data = tradingService.getHistoricalData(
                    fromDate, toDate, instrumentToken, interval, false, false);

            int candleCount = (data != null && data.dataArrayList != null) ? data.dataArrayList.size() : 0;
            log.info("Fetched {} candles for token {} on {}", candleCount, instrumentToken, date);

            if (candleCount == 0) {
                throw new BacktestException(BacktestException.ErrorCode.DATA_FETCH_FAILED,
                        "No candle data returned for token " + instrumentToken + " on " + date
                                + ". This may be a non-trading day or the instrument was not listed.");
            }

            return data;

        } catch (KiteException e) {
            throw new BacktestException(BacktestException.ErrorCode.DATA_FETCH_FAILED,
                    "Kite API error fetching historical data for token " + instrumentToken
                            + " on " + date + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw new BacktestException(BacktestException.ErrorCode.DATA_FETCH_FAILED,
                    "IO error fetching historical data for token " + instrumentToken
                            + " on " + date + ": " + e.getMessage(), e);
        }
    }


    private void sleepForRateLimit() {
        long delayMs = backtestConfig.getRateLimitDelayMs();
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Rate limit sleep interrupted");
            }
        }
    }
}

