package com.tradingbot.service.strategy;

import com.tradingbot.config.VolatilityConfig;
import com.tradingbot.service.TradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.LTPQuote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Volatility Filter Service
 *
 * Evaluates India VIX conditions to determine if straddle placement should proceed.
 * Implements three configurable rules:
 * 1. Current VIX > previous trading day VIX close
 * 2. Current VIX > absolute threshold (default: 12.5)
 * 3. 5-minute VIX percentage change > threshold (default: +0.3%)
 *
 * Thread-safety: Uses AtomicReference for cached data. All state is passed via
 * method parameters or retrieved fresh. No static mutable state.
 *
 * Extensibility: New rules can be added by implementing additional check methods
 * and registering them in shouldAllowTrade().
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VolatilityFilterService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final VolatilityConfig volatilityConfig;
    private final TradingService tradingService;

    /**
     * Cached VIX data to minimize API calls.
     * Contains current VIX, previous close, and 5-minute ago value.
     */
    private final AtomicReference<VixDataSnapshot> cachedSnapshot = new AtomicReference<>();

    /**
     * Result of volatility filter evaluation with detailed reasoning.
     */
    public record VolatilityFilterResult(
            boolean allowed,
            String reason,
            BigDecimal currentVix,
            BigDecimal previousClose,
            BigDecimal fiveMinuteAgoVix,
            BigDecimal percentageChange,
            List<String> passedRules,
            List<String> failedRules
    ) {
        public static VolatilityFilterResult allowed(String reason, VixDataSnapshot snapshot,
                                                      List<String> passedRules, List<String> failedRules) {
            BigDecimal pctChange = calculatePercentageChange(snapshot.currentVix(), snapshot.fiveMinuteAgoVix());
            return new VolatilityFilterResult(true, reason, snapshot.currentVix(),
                    snapshot.previousDayClose(), snapshot.fiveMinuteAgoVix(), pctChange, passedRules, failedRules);
        }

        public static VolatilityFilterResult blocked(String reason, VixDataSnapshot snapshot,
                                                      List<String> passedRules, List<String> failedRules) {
            BigDecimal pctChange = calculatePercentageChange(snapshot.currentVix(), snapshot.fiveMinuteAgoVix());
            return new VolatilityFilterResult(false, reason, snapshot.currentVix(),
                    snapshot.previousDayClose(), snapshot.fiveMinuteAgoVix(), pctChange, passedRules, failedRules);
        }

        public static VolatilityFilterResult dataUnavailable(boolean allowed, String reason) {
            return new VolatilityFilterResult(allowed, reason, null, null, null, null,
                    Collections.emptyList(), Collections.emptyList());
        }

        private static BigDecimal calculatePercentageChange(BigDecimal current, BigDecimal previous) {
            if (current == null || previous == null || previous.compareTo(BigDecimal.ZERO) == 0) {
                return null;
            }
            return current.subtract(previous)
                    .divide(previous, SCALE, ROUNDING)
                    .multiply(new BigDecimal("100"));
        }
    }

    /**
     * Snapshot of VIX data at a point in time.
     */
    public record VixDataSnapshot(
            BigDecimal currentVix,
            BigDecimal previousDayClose,
            BigDecimal fiveMinuteAgoVix,
            Instant fetchTime
    ) {}

    /**
     * Main entry point: Determines if straddle placement should proceed based on VIX conditions.
     *
     * @param isBacktest true if running in backtest/historical replay mode
     * @return VolatilityFilterResult with decision and detailed reasoning
     */
    public VolatilityFilterResult shouldAllowTrade(boolean isBacktest) {
        // Check if filter is enabled
        if (!volatilityConfig.isEnabled()) {
            log.debug("Volatility filter is disabled, allowing trade");
            return VolatilityFilterResult.dataUnavailable(true, "Volatility filter disabled");
        }

        // Check backtest mode
        if (isBacktest && !volatilityConfig.isBacktestEnabled()) {
            log.debug("Volatility filter disabled for backtest mode, allowing trade");
            return VolatilityFilterResult.dataUnavailable(true, "Volatility filter disabled for backtest");
        }

        // Fetch VIX data
        VixDataSnapshot snapshot;
        try {
            snapshot = getVixData();
        } catch (Exception e) {
            log.warn("Failed to fetch VIX data: {}", e.getMessage());
            boolean allow = volatilityConfig.isAllowOnDataUnavailable();
            return VolatilityFilterResult.dataUnavailable(allow,
                    "VIX data unavailable: " + e.getMessage() + ". Fail-safe: " + (allow ? "ALLOW" : "BLOCK"));
        }

        if (snapshot == null || snapshot.currentVix() == null) {
            log.warn("VIX data is null or incomplete");
            boolean allow = volatilityConfig.isAllowOnDataUnavailable();
            return VolatilityFilterResult.dataUnavailable(allow,
                    "VIX data unavailable. Fail-safe: " + (allow ? "ALLOW" : "BLOCK"));
        }

        // Evaluate rules
        return evaluateRules(snapshot);
    }

    /**
     * Evaluate all volatility rules against the current VIX snapshot.
     * Trade is allowed if ANY rule passes (OR logic).
     */
    private VolatilityFilterResult evaluateRules(VixDataSnapshot snapshot) {
        List<String> passedRules = new ArrayList<>();
        List<String> failedRules = new ArrayList<>();

        BigDecimal currentVix = snapshot.currentVix();
        BigDecimal previousClose = snapshot.previousDayClose();
        BigDecimal fiveMinAgo = snapshot.fiveMinuteAgoVix();

        // Rule 1: Current VIX > Previous Day Close
        if (previousClose != null) {
            if (currentVix.compareTo(previousClose) > 0) {
                passedRules.add(String.format("VIX_ABOVE_PREV_CLOSE: %.2f > %.2f",
                        currentVix, previousClose));
            } else {
                failedRules.add(String.format("VIX_ABOVE_PREV_CLOSE: %.2f <= %.2f",
                        currentVix, previousClose));
            }
        } else {
            failedRules.add("VIX_ABOVE_PREV_CLOSE: Previous close unavailable");
        }

        // Rule 2: Current VIX > Absolute Threshold
        BigDecimal absThreshold = volatilityConfig.getAbsoluteThreshold();
        if (currentVix.compareTo(absThreshold) > 0) {
            passedRules.add(String.format("VIX_ABOVE_THRESHOLD: %.2f > %.2f",
                    currentVix, absThreshold));
        } else {
            failedRules.add(String.format("VIX_ABOVE_THRESHOLD: %.2f <= %.2f",
                    currentVix, absThreshold));
        }

        // Rule 3: 5-minute percentage change > threshold
        if (fiveMinAgo != null && fiveMinAgo.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal pctChange = currentVix.subtract(fiveMinAgo)
                    .divide(fiveMinAgo, SCALE, ROUNDING)
                    .multiply(new BigDecimal("100"));
            BigDecimal pctThreshold = volatilityConfig.getPercentageChangeThreshold();

            if (pctChange.compareTo(pctThreshold) > 0) {
                passedRules.add(String.format("VIX_5MIN_CHANGE: %.2f%% > %.2f%%",
                        pctChange, pctThreshold));
            } else {
                failedRules.add(String.format("VIX_5MIN_CHANGE: %.2f%% <= %.2f%%",
                        pctChange, pctThreshold));
            }
        } else {
            failedRules.add("VIX_5MIN_CHANGE: 5-minute ago VIX unavailable");
        }

        // Trade allowed if ANY rule passed (OR logic)
        if (!passedRules.isEmpty()) {
            String reason = "VIX conditions favorable. Passed: " + String.join(", ", passedRules);
            log.info("Volatility filter PASSED: {}", reason);
            return VolatilityFilterResult.allowed(reason, snapshot, passedRules, failedRules);
        } else {
            String reason = "VIX flat or falling. All rules failed: " + String.join(", ", failedRules);
            log.info("Volatility filter BLOCKED: {}", reason);
            return VolatilityFilterResult.blocked(reason, snapshot, passedRules, failedRules);
        }
    }

    /**
     * Fetch VIX data from market APIs with caching.
     * Returns cached data if within TTL, otherwise fetches fresh data.
     */
    private VixDataSnapshot getVixData() {
        VixDataSnapshot cached = cachedSnapshot.get();
        if (cached != null && !isCacheExpired(cached)) {
            log.debug("Using cached VIX data: current={}, prevClose={}, 5minAgo={}",
                    cached.currentVix(), cached.previousDayClose(), cached.fiveMinuteAgoVix());
            return cached;
        }

        // Fetch fresh data
        VixDataSnapshot fresh = fetchVixDataFromApi();
        cachedSnapshot.set(fresh);
        return fresh;
    }

    private boolean isCacheExpired(VixDataSnapshot snapshot) {
        if (snapshot == null || snapshot.fetchTime() == null) {
            return true;
        }
        long ageMs = Duration.between(snapshot.fetchTime(), Instant.now()).toMillis();
        return ageMs > volatilityConfig.getCacheTtlMs();
    }

    /**
     * Fetch VIX data from Kite APIs.
     * - Current VIX: via LTP API
     * - Previous day close: via Historical Data API (previous trading day)
     * - 5-minute ago VIX: via Historical Data API (last 10 minutes, take first candle)
     */
    private VixDataSnapshot fetchVixDataFromApi() {
        String vixSymbol = volatilityConfig.getVixSymbol();
        String vixToken = volatilityConfig.getVixInstrumentToken();

        // Fetch current VIX via LTP
        BigDecimal currentVix = fetchCurrentVix(vixSymbol);

        // Fetch previous day close
        BigDecimal previousClose = fetchPreviousDayClose(vixToken);

        // Fetch 5-minute ago VIX
        BigDecimal fiveMinAgo = fetchFiveMinuteAgoVix(vixToken);

        VixDataSnapshot snapshot = new VixDataSnapshot(currentVix, previousClose, fiveMinAgo, Instant.now());
        log.info("Fetched VIX data: current={}, prevClose={}, 5minAgo={}",
                currentVix, previousClose, fiveMinAgo);

        return snapshot;
    }

    private BigDecimal fetchCurrentVix(String vixSymbol) {
        try {
            Map<String, LTPQuote> ltp = tradingService.getLTP(new String[]{vixSymbol});
            if (ltp != null && ltp.containsKey(vixSymbol)) {
                double price = ltp.get(vixSymbol).lastPrice;
                return BigDecimal.valueOf(price).setScale(SCALE, ROUNDING);
            }
        } catch (KiteException | IOException e) {
            log.warn("Failed to fetch current VIX LTP: {}", e.getMessage());
        }
        return null;
    }

    private BigDecimal fetchPreviousDayClose(String vixToken) {
        try {
            // Get previous trading day (skip weekends)
            LocalDate today = LocalDate.now(IST);
            LocalDate previousTradingDay = getPreviousTradingDay(today);

            Date from = Date.from(previousTradingDay.atStartOfDay(IST).toInstant());
            Date to = Date.from(previousTradingDay.atTime(23, 59, 59).atZone(IST).toInstant());

            HistoricalData data = tradingService.getHistoricalData(from, to, vixToken, "day", false, false);

            if (data != null && data.dataArrayList != null && !data.dataArrayList.isEmpty()) {
                // Get the close price of the last candle (previous day close)
                HistoricalData lastCandle = data.dataArrayList.get(data.dataArrayList.size() - 1);
                return BigDecimal.valueOf(lastCandle.close).setScale(SCALE, ROUNDING);
            }
        } catch (KiteException | IOException e) {
            log.warn("Failed to fetch previous day VIX close: {}", e.getMessage());
        }
        return null;
    }

    private BigDecimal fetchFiveMinuteAgoVix(String vixToken) {
        try {
            ZonedDateTime now = ZonedDateTime.now(IST);
            ZonedDateTime tenMinutesAgo = now.minusMinutes(10);

            Date from = Date.from(tenMinutesAgo.toInstant());
            Date to = Date.from(now.toInstant());

            HistoricalData data = tradingService.getHistoricalData(from, to, vixToken, "5minute", false, false);

            if (data != null && data.dataArrayList != null && data.dataArrayList.size() >= 2) {
                // Get the close of the candle from 5 minutes ago (second to last candle)
                HistoricalData fiveMinAgoCandle = data.dataArrayList.get(data.dataArrayList.size() - 2);
                return BigDecimal.valueOf(fiveMinAgoCandle.close).setScale(SCALE, ROUNDING);
            } else if (data != null && data.dataArrayList != null && !data.dataArrayList.isEmpty()) {
                // Fallback: use the open of the first available candle
                HistoricalData firstCandle = data.dataArrayList.get(0);
                return BigDecimal.valueOf(firstCandle.open).setScale(SCALE, ROUNDING);
            }
        } catch (KiteException | IOException e) {
            log.warn("Failed to fetch 5-minute ago VIX: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Get the previous trading day (skip weekends).
     */
    private LocalDate getPreviousTradingDay(LocalDate date) {
        LocalDate previous = date.minusDays(1);
        // Skip weekends
        while (previous.getDayOfWeek() == DayOfWeek.SATURDAY ||
               previous.getDayOfWeek() == DayOfWeek.SUNDAY) {
            previous = previous.minusDays(1);
        }
        return previous;
    }

    /**
     * Clear the cached VIX data. Useful for testing or forced refresh.
     */
    public void clearCache() {
        cachedSnapshot.set(null);
        log.debug("VIX data cache cleared");
    }
}

