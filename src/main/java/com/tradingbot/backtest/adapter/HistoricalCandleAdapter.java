package com.tradingbot.backtest.adapter;

import com.zerodhatech.models.HistoricalData;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Converts Kite SDK {@link HistoricalData} candle arrays into a flat list of
 * {@link SimulatedCandle} records suitable for backtest simulation.
 * <p>
 * Each candle's <b>close price</b> is used as the simulated LTP at that minute.
 * Pure utility — no Spring dependency.
 */
@UtilityClass
@Slf4j
public class HistoricalCandleAdapter {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * A single simulated candle carrying the instrument context.
     */
    public record SimulatedCandle(
            LocalDateTime timestamp,
            double open,
            double high,
            double low,
            double close,
            long instrumentToken
    ) {}

    /**
     * Converts Kite historical data into a list of simulated candles.
     *
     * @param historicalData Kite SDK result containing dataArrayList
     * @param instrumentToken the instrument token to tag each candle with
     * @return sorted list of SimulatedCandle (ascending by timestamp), empty list if input is null/empty
     */
    public static List<SimulatedCandle> convert(HistoricalData historicalData, long instrumentToken) {
        if (historicalData == null || historicalData.dataArrayList == null || historicalData.dataArrayList.isEmpty()) {
            log.warn("No candle data to convert for instrument token {}", instrumentToken);
            return Collections.emptyList();
        }

        List<SimulatedCandle> candles = new ArrayList<>(historicalData.dataArrayList.size());

        for (HistoricalData candle : historicalData.dataArrayList) {
            LocalDateTime timestamp = toLocalDateTime(candle.timeStamp);
            if (timestamp == null) {
                log.debug("Skipping candle with null timestamp for token {}", instrumentToken);
                continue;
            }

            candles.add(new SimulatedCandle(
                    timestamp,
                    candle.open,
                    candle.high,
                    candle.low,
                    candle.close,
                    instrumentToken
            ));
        }

        log.debug("Converted {} candles for instrument token {}", candles.size(), instrumentToken);
        return candles;
    }

    /**
     * Converts a Kite candle timestamp (String in ISO format) to LocalDateTime in IST.
     * <p>
     * Handles multiple formats from Kite SDK:
     * <ul>
     *   <li>{@code "2025-01-15T09:15:00+0530"} (no colon in offset)</li>
     *   <li>{@code "2025-01-15T09:15:00+05:30"} (colon in offset)</li>
     *   <li>{@code "2025-01-15T09:15:00"} (no offset, assumed IST)</li>
     * </ul>
     */
    private static LocalDateTime toLocalDateTime(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            // Normalize Kite's "+0530" offset to standard "+05:30" for ZonedDateTime parsing
            String normalized = timestamp;
            // Match pattern like +0530 or -0530 (4-digit offset without colon)
            if (normalized.matches(".*[+-]\\d{4}$")) {
                int len = normalized.length();
                normalized = normalized.substring(0, len - 2) + ":" + normalized.substring(len - 2);
            }
            ZonedDateTime zdt = ZonedDateTime.parse(normalized);
            return zdt.withZoneSameInstant(IST).toLocalDateTime();
        } catch (Exception e) {
            // Fallback: try parsing as LocalDateTime (no offset — assumed IST)
            try {
                return LocalDateTime.parse(timestamp.substring(0, Math.min(19, timestamp.length())));
            } catch (Exception e2) {
                log.warn("Failed to parse candle timestamp: {}", timestamp);
                return null;
            }
        }
    }
}



