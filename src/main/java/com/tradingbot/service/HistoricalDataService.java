package com.tradingbot.service;

import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class HistoricalDataService {

    private final TradingService tradingService;

    public static class DayRange {
        public final Date start;
        public final Date end;
        public final LocalDate localDate;
        public DayRange(Date start, Date end, LocalDate localDate) {
            this.start = start; this.end = end; this.localDate = localDate;
        }
    }

    /**
     * Determine the most recent trading day window (09:15 to 15:30 IST).
     * If current day is a weekday and time > market close, choose today; otherwise previous weekday.
     */
    public DayRange mostRecentTradingDayWindow() {
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        ZonedDateTime now = ZonedDateTime.now(ist);

        LocalDate candidate = now.toLocalDate();
        LocalTime marketClose = LocalTime.of(15, 30);

        // If before market close today, use previous weekday
        if (now.toLocalTime().isBefore(marketClose)) {
            candidate = previousWeekday(candidate);
        }
        // If weekend, move to previous weekday
        if (isWeekend(candidate)) {
            candidate = previousWeekday(candidate);
        }

        ZonedDateTime startZdt = candidate.atTime(9, 15).atZone(ist);
        ZonedDateTime endZdt = candidate.atTime(15, 30).atZone(ist);

        return new DayRange(Date.from(startZdt.toInstant()), Date.from(endZdt.toInstant()), candidate);
    }

    private boolean isWeekend(LocalDate d) {
        DayOfWeek dow = d.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    private LocalDate previousWeekday(LocalDate d) {
        LocalDate prev = d.minusDays(1);
        while (isWeekend(prev)) prev = prev.minusDays(1);
        return prev;
    }

    /**
     * Fetch historical candles for a token and expand to per-second prices using simple linear interpolation from open to close per minute.
     * Returns a TreeMap keyed by epoch second in IST zone to price.
     */
    public NavigableMap<Long, Double> getSecondWisePricesForToken(long instrumentToken,
                                                                  Date from,
                                                                  Date to) {
        NavigableMap<Long, Double> secondPrices = new TreeMap<>();
        try {
            String tokenStr = String.valueOf(instrumentToken);
            HistoricalData hd = tradingService.getHistoricalData(from, to, tokenStr, "minute", false, false);

            if (hd == null || hd.dataArrayList == null || hd.dataArrayList.isEmpty()) {
                log.warn("No historical data returned for token {} between {} and {}", instrumentToken, from, to);
                return secondPrices;
            }

            ZoneId ist = ZoneId.of("Asia/Kolkata");
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            for (HistoricalData candle : hd.dataArrayList) {
                String tsStr = candle.timeStamp; // e.g. "2023-08-14 09:15:00"
                double open = candle.open;
                double close = candle.close;
                if (tsStr == null || tsStr.isEmpty() || open <= 0 || close <= 0) continue;

                LocalDateTime ldt;
                try {
                    ldt = LocalDateTime.parse(tsStr, formatter);
                } catch (Exception pe) {
                    log.warn("Failed to parse candle timestamp '{}' for token {}", tsStr, instrumentToken);
                    continue;
                }
                long startEpoch = ldt.atZone(ist).toEpochSecond();
                long endEpoch = startEpoch + 60; // next minute start (exclusive)

                // Linear interpolation from open to close across 60 seconds
                for (long sec = startEpoch; sec < endEpoch; sec++) {
                    double t = (sec - startEpoch) / 60.0;
                    double price = open + (close - open) * t;
                    secondPrices.put(sec, price);
                }
            }
            log.info("Generated {} second-wise price points for token {}", secondPrices.size(), instrumentToken);
        } catch (KiteException | IOException e) {
            log.error("Error fetching historical data for token {}: {}", instrumentToken, e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error generating second-wise prices for token {}: {}", instrumentToken, e.getMessage(), e);
        }
        return secondPrices;
    }
}
