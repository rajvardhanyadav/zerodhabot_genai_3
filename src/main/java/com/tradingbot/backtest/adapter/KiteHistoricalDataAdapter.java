package com.tradingbot.backtest.adapter;

import com.tradingbot.backtest.dto.CandleData;
import com.tradingbot.service.TradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Instrument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Implementation of HistoricalDataAdapter using Kite Connect API.
 *
 * This adapter translates between the backtest module's CandleData format
 * and the Kite SDK's HistoricalData format.
 *
 * Thread-safe implementation with instrument token caching.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KiteHistoricalDataAdapter implements HistoricalDataAdapter {

    private final TradingService tradingService;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // Instrument token cache: symbol -> token
    private final Map<String, String> instrumentTokenCache = new ConcurrentHashMap<>();

    // Index instrument tokens (well-known)
    private static final Map<String, String> INDEX_TOKENS = Map.of(
            "NIFTY", "256265",      // NIFTY 50
            "BANKNIFTY", "260105"   // NIFTY BANK
    );

    @Override
    public List<CandleData> fetchCandles(
            String instrumentToken,
            LocalDate fromDate,
            LocalDate toDate,
            String interval
    ) throws HistoricalDataException {
        return fetchCandles(
                instrumentToken,
                fromDate.atTime(9, 15),
                toDate.atTime(15, 30),
                interval
        );
    }

    @Override
    public List<CandleData> fetchCandles(
            String instrumentToken,
            LocalDateTime fromDateTime,
            LocalDateTime toDateTime,
            String interval
    ) throws HistoricalDataException {
        try {
            Date from = Date.from(fromDateTime.atZone(IST).toInstant());
            Date to = Date.from(toDateTime.atZone(IST).toInstant());

            log.debug("Fetching historical data: token={}, from={}, to={}, interval={}",
                    instrumentToken, fromDateTime, toDateTime, interval);

            HistoricalData historicalData = tradingService.getHistoricalData(
                    from, to, instrumentToken, interval, false, false
            );

            if (historicalData == null || historicalData.dataArrayList == null) {
                log.warn("No historical data returned for token={}", instrumentToken);
                return Collections.emptyList();
            }

            return historicalData.dataArrayList.stream()
                    .map(hd -> mapToCandle(hd, instrumentToken))
                    .sorted(Comparator.comparing(CandleData::getTimestamp))
                    .collect(Collectors.toList());

        } catch (KiteException | IOException e) {
            log.error("Failed to fetch historical data for token={}: {}", instrumentToken, e.getMessage());
            throw new HistoricalDataException("Failed to fetch historical data: " + e.getMessage(), e);
        }
    }

    @Override
    public List<CandleData> fetchCandlesBySymbol(
            String tradingSymbol,
            String exchange,
            LocalDate date,
            String interval
    ) throws HistoricalDataException {
        String token = resolveInstrumentToken(tradingSymbol, exchange);
        return fetchCandles(token, date, date, interval);
    }

    @Override
    public List<CandleData> fetchIndexCandles(
            String indexName,
            LocalDate date,
            String interval
    ) throws HistoricalDataException {
        String token = INDEX_TOKENS.get(indexName.toUpperCase());
        if (token == null) {
            throw new HistoricalDataException("Unknown index: " + indexName);
        }
        List<CandleData> candles = fetchCandles(token, date, date, interval);

        // Set trading symbol for index candles
        candles.forEach(c -> c.setTradingSymbol(indexName.toUpperCase()));
        return candles;
    }

    @Override
    public String resolveInstrumentToken(String tradingSymbol, String exchange)
            throws HistoricalDataException {
        String cacheKey = exchange + ":" + tradingSymbol;

        return instrumentTokenCache.computeIfAbsent(cacheKey, key -> {
            try {
                List<Instrument> instruments = tradingService.getInstruments(exchange);
                return instruments.stream()
                        .filter(i -> tradingSymbol.equals(i.getTradingsymbol()))
                        .findFirst()
                        .map(i -> String.valueOf(i.getInstrument_token()))
                        .orElseThrow(() -> new HistoricalDataException(
                                "Instrument not found: " + tradingSymbol + " on " + exchange));
            } catch (KiteException | IOException e) {
                throw new HistoricalDataException("Failed to resolve instrument token: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public boolean isDataAvailable(LocalDate date) {
        // Basic check: weekends are non-trading days
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        // For more accurate check, would need holiday calendar
        // For now, assume weekdays are trading days
        return true;
    }

    @Override
    public List<CandleData> fetchOptionCandles(
            String instrumentType,
            BigDecimal strike,
            String optionType,
            LocalDate expiryDate,
            LocalDate date,
            String interval
    ) throws HistoricalDataException {
        // Find instrument by matching expiry, strike, and option type - same as live trading
        Instrument instrument = findOptionInstrument(instrumentType, strike, optionType, expiryDate);

        if (instrument == null) {
            // Fallback: try generating symbol manually
            String tradingSymbol = generateOptionSymbol(instrumentType, strike, optionType, expiryDate);
            throw new HistoricalDataException(
                    "Option instrument not found for " + instrumentType + " " + strike + " " + optionType +
                    " expiry " + expiryDate + ". Tried symbol: " + tradingSymbol);
        }

        String tradingSymbol = instrument.getTradingsymbol();
        String token = String.valueOf(instrument.getInstrument_token());

        log.debug("Fetching option candles: symbol={}, token={}, date={}, interval={}",
                tradingSymbol, token, date, interval);

        try {
            List<CandleData> candles = fetchCandles(token, date, date, interval);

            // Set trading symbol for all candles
            candles.forEach(c -> c.setTradingSymbol(tradingSymbol));

            log.debug("Fetched {} candles for option {}", candles.size(), tradingSymbol);
            return candles;

        } catch (HistoricalDataException e) {
            log.warn("Failed to fetch option candles for {}: {}", tradingSymbol, e.getMessage());
            throw e;
        }
    }

    /**
     * Find option instrument by matching expiry, strike, and option type.
     * This is the same approach used by live trading to ensure consistency.
     *
     * @param instrumentType underlying instrument (NIFTY, BANKNIFTY)
     * @param strike strike price
     * @param optionType CE or PE
     * @param expiryDate expiry date
     * @return Instrument or null if not found
     */
    private Instrument findOptionInstrument(String instrumentType, BigDecimal strike,
                                             String optionType, LocalDate expiryDate) {
        try {
            List<Instrument> instruments = tradingService.getInstruments("NFO");
            String underlyingName = getUnderlyingName(instrumentType);
            double targetStrike = strike.doubleValue();

            // Convert LocalDate to Date for comparison
            Date expiryDateAsDate = Date.from(expiryDate.atStartOfDay(IST).toInstant());

            for (Instrument inst : instruments) {
                // Match underlying name
                if (!underlyingName.equals(inst.name)) continue;

                // Match option type (CE/PE)
                if (!optionType.equalsIgnoreCase(inst.instrument_type)) continue;

                // Match expiry date
                if (inst.getExpiry() == null) continue;
                if (!isSameDay(inst.getExpiry(), expiryDateAsDate)) continue;

                // Match strike price
                try {
                    double instStrike = Double.parseDouble(inst.strike);
                    if (Math.abs(instStrike - targetStrike) < 0.01) {
                        log.debug("Found instrument: {} for {} {} {} expiry {}",
                                inst.getTradingsymbol(), instrumentType, strike, optionType, expiryDate);
                        return inst;
                    }
                } catch (NumberFormatException e) {
                    // Skip invalid instruments
                }
            }

            log.warn("No instrument found for {} {} {} expiry {}",
                    instrumentType, strike, optionType, expiryDate);
            return null;

        } catch (KiteException | IOException e) {
            log.error("Failed to fetch instruments from NFO: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Check if two dates are the same day.
     */
    private boolean isSameDay(Date date1, Date date2) {
        LocalDate ld1 = date1.toInstant().atZone(IST).toLocalDate();
        LocalDate ld2 = date2.toInstant().atZone(IST).toLocalDate();
        return ld1.equals(ld2);
    }

    /**
     * Get the underlying name for Kite API matching.
     */
    private String getUnderlyingName(String instrumentType) {
        return switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> "NIFTY";
            case "BANKNIFTY" -> "BANKNIFTY";
            default -> instrumentType.toUpperCase();
        };
    }

    @Override
    public String generateOptionSymbol(
            String instrumentType,
            BigDecimal strike,
            String optionType,
            LocalDate expiryDate
    ) {
        // Try to find the actual instrument first to get the correct symbol
        Instrument instrument = findOptionInstrument(instrumentType, strike, optionType, expiryDate);
        if (instrument != null) {
            return instrument.getTradingsymbol();
        }

        // Fallback: Generate symbol using NSE format (NIFTY + YY + MMM + STRIKE + CE/PE)
        // This matches the format used in BaseStrategy.buildOptionInstrumentIdentifierManually
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyMMM");
        String expiryStr = expiryDate.format(formatter).toUpperCase();

        String symbol = String.format("%s%s%d%s",
                instrumentType.toUpperCase(),
                expiryStr,
                strike.intValue(),
                optionType.toUpperCase());

        log.debug("Generated fallback option symbol: {} for {}/{}/{}/{}",
                symbol, instrumentType, strike, optionType, expiryDate);

        return symbol;
    }


    /**
     * Map Kite SDK HistoricalData to our CandleData DTO.
     *
     * Note: Kite SDK's HistoricalData.timeStamp is a String in format "yyyy-MM-dd'T'HH:mm:ssXXX"
     */
    private CandleData mapToCandle(HistoricalData hd, String instrumentToken) {
        LocalDateTime timestamp = parseKiteTimestamp(hd.timeStamp);

        return CandleData.builder()
                .timestamp(timestamp)
                .open(BigDecimal.valueOf(hd.open))
                .high(BigDecimal.valueOf(hd.high))
                .low(BigDecimal.valueOf(hd.low))
                .close(BigDecimal.valueOf(hd.close))
                .volume(hd.volume)
                .openInterest(hd.oi)
                .instrumentToken(instrumentToken)
                .build();
    }

    /**
     * Parse Kite's timestamp string to LocalDateTime.
     * Kite format: "yyyy-MM-dd'T'HH:mm:ss+0530" (ISO 8601 with offset without colon)
     */
    private LocalDateTime parseKiteTimestamp(String timestamp) {
        try {
            // Try parsing as ISO date-time with offset
            java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(timestamp);
            return odt.atZoneSameInstant(IST).toLocalDateTime();
        } catch (java.time.format.DateTimeParseException e) {
            // Kite returns offset without colon (e.g., +0530 instead of +05:30)
            // Insert colon in timezone offset if missing
            try {
                String normalizedTimestamp = normalizeTimezoneOffset(timestamp);
                java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(normalizedTimestamp);
                return odt.atZoneSameInstant(IST).toLocalDateTime();
            } catch (java.time.format.DateTimeParseException e2) {
                // Fallback: try parsing without offset (local datetime)
                try {
                    return LocalDateTime.parse(timestamp.replace(" ", "T"));
                } catch (java.time.format.DateTimeParseException e3) {
                    log.warn("Failed to parse Kite timestamp: {}, using current time", timestamp);
                    return LocalDateTime.now(IST);
                }
            }
        }
    }

    /**
     * Normalize timezone offset by inserting colon if missing.
     * Converts "+0530" to "+05:30" or "-0530" to "-05:30"
     */
    private String normalizeTimezoneOffset(String timestamp) {
        // Pattern: ends with +HHMM or -HHMM (4 digits without colon)
        if (timestamp.length() >= 5) {
            int len = timestamp.length();
            char signChar = timestamp.charAt(len - 5);
            if ((signChar == '+' || signChar == '-') &&
                Character.isDigit(timestamp.charAt(len - 4)) &&
                Character.isDigit(timestamp.charAt(len - 3)) &&
                Character.isDigit(timestamp.charAt(len - 2)) &&
                Character.isDigit(timestamp.charAt(len - 1))) {
                // Insert colon: +0530 -> +05:30
                return timestamp.substring(0, len - 2) + ":" + timestamp.substring(len - 2);
            }
        }
        return timestamp;
    }
}


