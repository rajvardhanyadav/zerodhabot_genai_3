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

    // Lightweight constants and formatters for consistent logging in IST
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final DateTimeFormatter CANDLE_TS_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TS_LOG_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
    private static final DateTimeFormatter CANDLE_TS_OFFSET_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
    private static final DateTimeFormatter CANDLE_TS_OFFSET_MILLIS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

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
        ZonedDateTime now = ZonedDateTime.now(IST);
        if (log.isDebugEnabled()) {
            log.debug("Computing most recent trading day window. Now(IST)={}", now);
        }

        LocalDate candidate = now.toLocalDate();
        LocalTime marketClose = MARKET_CLOSE;

        // If before market close today, use previous weekday
        if (now.toLocalTime().isBefore(marketClose)) {
            if (log.isDebugEnabled()) {
                log.debug("Current time {} before market close {}; selecting previous weekday instead of {}", now.toLocalTime(), marketClose, candidate);
            }
            candidate = previousWeekday(candidate);
        }
        // If weekend, move to previous weekday
        if (isWeekend(candidate)) {
            if (log.isDebugEnabled()) {
                log.debug("Candidate {} falls on weekend; rolling back to previous weekday", candidate);
            }
            candidate = previousWeekday(candidate);
        }

        ZonedDateTime startZdt = candidate.atTime(MARKET_OPEN).atZone(IST);
        ZonedDateTime endZdt = candidate.atTime(MARKET_CLOSE).atZone(IST);

        if (log.isDebugEnabled()) {
            log.debug("Most recent trading day determined as {} with window {} to {} (IST)", candidate, startZdt, endZdt);
        }

        return new DayRange(Date.from(startZdt.toInstant()), Date.from(endZdt.toInstant()), candidate);
    }

    private boolean isWeekend(LocalDate d) {
        DayOfWeek dow = d.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    private LocalDate previousWeekday(LocalDate d) {
        LocalDate prev = d.minusDays(1);
        while (isWeekend(prev)) prev = prev.minusDays(1);
        if (log.isTraceEnabled()) {
            log.trace("previousWeekday resolved: input={}, previousWeekday={}", d, prev);
        }
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
        // Input validation and early diagnostics
        if (instrumentToken <= 0) {
            log.warn("Invalid instrument token provided: {}", instrumentToken);
            return secondPrices;
        }
        if (from == null || to == null) {
            log.error("From/To date(s) cannot be null. token={}, from={}, to={}", instrumentToken, from, to);
            return secondPrices;
        }
        if (!from.before(to)) {
            log.warn("'from' must be before 'to'. Swapping the range. token={}, from(IST)={}, to(IST)={}", instrumentToken, from, to);
            Date tmp = from; from = to; to = tmp;
            if (log.isDebugEnabled()) {
                log.debug("Swapped range -> from(IST)={}, to(IST)={}", from, to);
            }
        }

        if (log.isInfoEnabled()) {
            log.info("Fetching per-second prices for token {} between {} and {} (IST)", instrumentToken, fmtIst(from), fmtIst(to));
        }
        if (log.isDebugEnabled()) {
            long diffMin = Math.max(0, Duration.between(from.toInstant(), to.toInstant()).toMinutes());
            log.debug("Historical fetch request params: token={} interval=minute continuous=false oi=false expectedMinutes~{}", instrumentToken, diffMin);
        }

        try {
            String tokenStr = String.valueOf(instrumentToken);

            long t0 = System.nanoTime();
            HistoricalData hd = tradingService.getHistoricalData(from, to, tokenStr, "minute", false, false);
            long fetchMillis = Duration.ofNanos(System.nanoTime() - t0).toMillis();
            if (log.isDebugEnabled()) {
                log.debug("Historical data API returned in {} ms for token {}", fetchMillis, instrumentToken);
            }

            if (hd == null || hd.dataArrayList == null || hd.dataArrayList.isEmpty()) {
                log.warn("No historical data returned for token {} between {} and {}", instrumentToken, fmtIst(from), fmtIst(to));
                return secondPrices;
            }

            List<HistoricalData> candles = hd.dataArrayList;
            int candleCount = candles.size();
            if (log.isDebugEnabled()) {
                String firstTs = candles.get(0).timeStamp;
                String lastTs = candles.get(candleCount - 1).timeStamp;
                long expectedMinutes = Math.max(0, Duration.between(from.toInstant(), to.toInstant()).toMinutes());
                double coverage = expectedMinutes == 0 ? 0.0 : (candleCount * 100.0 / expectedMinutes);
                log.debug("Received {} minute candles for token {}. FirstTS={}, LastTS={}, expectedMinutes~{}, coverage~{}%", candleCount, instrumentToken, firstTs, lastTs, expectedMinutes, String.format(Locale.US, "%.2f", coverage));
            }

            int skippedInvalidOC = 0;
            int skippedTsParse = 0;
            int processedCandles = 0;

            for (int i = 0; i < candleCount; i++) {
                HistoricalData candle = candles.get(i);
                String tsStr = candle.timeStamp; // e.g. "2023-08-14 09:15:00"
                double open = candle.open;
                double close = candle.close;
                if (tsStr == null || tsStr.isEmpty() || open <= 0 || close <= 0) {
                    if (open <= 0 || close <= 0) skippedInvalidOC++;
                    else skippedTsParse++;
                    if (log.isTraceEnabled()) {
                        // Log additional fields if present (high/low) for context
                        try {
                            log.trace("Skipping candle idx={} ts='{}' OHLCHint=[o={}, h={}, l={}, c={}] (invalid)", i, tsStr, candle.open, candle.high, candle.low, candle.close);
                        } catch (Throwable ignore) {
                            log.trace("Skipping candle idx={} ts='{}' open={} close={} (invalid)", i, tsStr, open, close);
                        }
                    }
                    continue;
                }

                // Parse timestamp with flexible formats
                Optional<ZonedDateTime> parsed = parseCandleTimestamp(tsStr);
                if (parsed.isEmpty()) {
                    skippedTsParse++;
                    log.warn("Failed to parse candle timestamp '{}' for token {} (idx={})", tsStr, instrumentToken, i);
                    continue;
                }
                long startEpoch = parsed.get().toEpochSecond();
                long endEpoch = startEpoch + 60; // next minute start (exclusive)

                // Linear interpolation from open to close across 60 seconds
                for (long sec = startEpoch; sec < endEpoch; sec++) {
                    double t = (sec - startEpoch) / 60.0;
                    double price = open + (close - open) * t;
                    secondPrices.put(sec, price);
                }
                processedCandles++;

                if (log.isTraceEnabled() && (i < 2 || i >= candleCount - 2)) {
                    try {
                        log.trace("Interpolated seconds for candle idx={} ts={} [o={}, h={}, l={}, c={}] -> epoch [{} .. {})", i, tsStr, candle.open, candle.high, candle.low, candle.close, startEpoch, endEpoch);
                    } catch (Throwable ignore) {
                        log.trace("Interpolated seconds for candle idx={} ts={} [open={}, close={}] -> epoch [{} .. {})", i, tsStr, open, close, startEpoch, endEpoch);
                    }
                }
            }

            if (!secondPrices.isEmpty()) {
                Map.Entry<Long, Double> first = secondPrices.firstEntry();
                Map.Entry<Long, Double> last = secondPrices.lastEntry();
                if (log.isDebugEnabled()) {
                    double minPrice = Double.POSITIVE_INFINITY;
                    double maxPrice = Double.NEGATIVE_INFINITY;
                    for (double v : secondPrices.values()) {
                        if (v < minPrice) minPrice = v;
                        if (v > maxPrice) maxPrice = v;
                    }
                    log.debug("Second-wise sample: start={} ({}) price={} | end={} ({}) price={} | range[min={}, max={}]",
                            first.getKey(), fmtEpoch(first.getKey()), first.getValue(),
                            last.getKey(), fmtEpoch(last.getKey()), last.getValue(),
                            minPrice, maxPrice);
                }
            }

            if (processedCandles != candles.size()) {
                log.debug("Processed candles: {} of {} (skippedInvalidOC={}, skippedTsParse={})", processedCandles, candles.size(), skippedInvalidOC, skippedTsParse);
            }

            log.info("Generated {} second-wise price points for token {} (processedCandles={}, skippedInvalidOC={}, skippedTsParse={})",
                    secondPrices.size(), instrumentToken, processedCandles, skippedInvalidOC, skippedTsParse);
        } catch (KiteException | IOException e) {
            log.error("Error fetching historical data for token {}: {}", instrumentToken, e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error generating second-wise prices for token {}: {}", instrumentToken, e.getMessage(), e);
        }
        return secondPrices;
    }

    /**
     * Attempt to parse a candle timestamp supporting multiple formats:
     * 1) yyyy-MM-dd HH:mm:ss (no offset)
     * 2) yyyy-MM-dd'T'HH:mm:ssZ (offset like +0530)
     * 3) yyyy-MM-dd'T'HH:mm:ss.SSSZ (offset with millis)
     * 4) ISO_OFFSET_DATE_TIME (with or without millis, colon in offset) e.g. 2025-11-14T15:23:00+05:30 / 2025-11-14T15:23:00.000+05:30
     * Also normalizes offset without colon (e.g. +0530) to +05:30 for ISO parsing when needed.
     */
    private Optional<ZonedDateTime> parseCandleTimestamp(String ts) {
        if (ts == null || ts.isEmpty()) return Optional.empty();

        // Fast path: legacy format without offset
        try {
            LocalDateTime ldt = LocalDateTime.parse(ts, CANDLE_TS_FORMATTER);
            return Optional.of(ldt.atZone(IST));
        } catch (Exception ignore) { /* continue */ }

        // Try explicit offset patterns without milliseconds
        try {
            OffsetDateTime odt = OffsetDateTime.parse(ts, CANDLE_TS_OFFSET_FMT);
            return Optional.of(odt.atZoneSameInstant(IST));
        } catch (Exception ignore) { /* continue */ }

        // Try offset with millis pattern
        try {
            OffsetDateTime odt = OffsetDateTime.parse(ts, CANDLE_TS_OFFSET_MILLIS_FMT);
            return Optional.of(odt.atZoneSameInstant(IST));
        } catch (Exception ignore) { /* continue */ }

        // Normalize offset without colon for ISO parsing, e.g. +0530 -> +05:30
        if (ts.matches(".*[+-]\\d{4}$")) {
            String base = ts.substring(0, ts.length() - 5);
            String off = ts.substring(ts.length() - 5);
            String normalized = base + off.substring(0, 3) + ":" + off.substring(3);
            try {
                OffsetDateTime odt = OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                return Optional.of(odt.atZoneSameInstant(IST));
            } catch (Exception ignore) { /* continue */ }
        }

        // Try ISO_OFFSET_DATE_TIME directly (supports colon offset and optional millis)
        try {
            OffsetDateTime odt = OffsetDateTime.parse(ts, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            return Optional.of(odt.atZoneSameInstant(IST));
        } catch (Exception ignore) { /* continue */ }

        return Optional.empty();
    }

    // --- small helpers for consistent logging ---
    private String fmtIst(Date d) {
        if (d == null) return "null";
        return ZonedDateTime.ofInstant(d.toInstant(), IST).format(TS_LOG_FMT);
    }

    private String fmtEpoch(long epochSecond) {
        return Instant.ofEpochSecond(epochSecond).atZone(IST).format(TS_LOG_FMT);
    }
}
