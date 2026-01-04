package com.tradingbot.service.strategy;

import com.tradingbot.config.VolatilityConfig;
import com.tradingbot.service.TradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.LTPQuote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for VolatilityFilterService.
 * Tests all three VIX rules and fail-safe behavior.
 */
@ExtendWith(MockitoExtension.class)
class VolatilityFilterServiceTest {

    @Mock
    private TradingService tradingService;

    private VolatilityConfig volatilityConfig;
    private VolatilityFilterService volatilityFilterService;

    private static final String VIX_SYMBOL = "NSE:INDIA VIX";
    private static final String VIX_TOKEN = "264969";

    @BeforeEach
    void setUp() {
        volatilityConfig = new VolatilityConfig();
        volatilityConfig.setEnabled(true);
        volatilityConfig.setVixSymbol(VIX_SYMBOL);
        volatilityConfig.setVixInstrumentToken(VIX_TOKEN);
        volatilityConfig.setAbsoluteThreshold(new BigDecimal("12.5"));
        volatilityConfig.setPercentageChangeThreshold(new BigDecimal("0.3"));
        volatilityConfig.setBacktestEnabled(false);
        volatilityConfig.setAllowOnDataUnavailable(true);
        volatilityConfig.setCacheTtlMs(60000);

        volatilityFilterService = new VolatilityFilterService(volatilityConfig, tradingService);
    }

    @Nested
    @DisplayName("Filter Disabled Tests")
    class FilterDisabledTests {

        @Test
        @DisplayName("Should allow trade when filter is disabled")
        void shouldAllowTradeWhenFilterDisabled() {
            volatilityConfig.setEnabled(false);

            VolatilityFilterService.VolatilityFilterResult result =
                    volatilityFilterService.shouldAllowTrade(false);

            assertTrue(result.allowed());
            assertEquals("Volatility filter disabled", result.reason());
        }

        @Test
        @DisplayName("Should allow trade in backtest mode when backtest filter is disabled")
        void shouldAllowTradeInBacktestWhenBacktestFilterDisabled() {
            volatilityConfig.setBacktestEnabled(false);

            VolatilityFilterService.VolatilityFilterResult result =
                    volatilityFilterService.shouldAllowTrade(true);

            assertTrue(result.allowed());
            assertEquals("Volatility filter disabled for backtest", result.reason());
        }
    }

    @Nested
    @DisplayName("Rule 1: VIX Above Previous Close Tests")
    class VixAbovePreviousCloseTests {

        @Test
        @DisplayName("Should allow trade when current VIX > previous day close")
        void shouldAllowWhenVixAbovePreviousClose() throws KiteException, IOException {
            // Current VIX = 13.5, Previous close = 12.0
            mockLTP(13.5);
            mockPreviousDayClose(12.0);
            mockFiveMinuteAgoVix(13.4); // Small increase, but below threshold

            volatilityFilterService.clearCache();
            VolatilityFilterService.VolatilityFilterResult result =
                    volatilityFilterService.shouldAllowTrade(false);

            assertTrue(result.allowed());
            assertTrue(result.passedRules().stream()
                    .anyMatch(r -> r.contains("VIX_ABOVE_PREV_CLOSE")));
        }
    }

    @Nested
    @DisplayName("Rule 2: VIX Above Absolute Threshold Tests")
    class VixAboveThresholdTests {

        @Test
        @DisplayName("Should allow trade when current VIX > absolute threshold")
        void shouldAllowWhenVixAboveThreshold() throws KiteException, IOException {
            // Current VIX = 14.0, threshold = 12.5
            mockLTP(14.0);
            mockPreviousDayClose(15.0); // Higher than current (rule 1 fails)
            mockFiveMinuteAgoVix(13.98); // Small change (rule 3 fails)

            volatilityFilterService.clearCache();
            VolatilityFilterService.VolatilityFilterResult result =
                    volatilityFilterService.shouldAllowTrade(false);

            assertTrue(result.allowed());
            assertTrue(result.passedRules().stream()
                    .anyMatch(r -> r.contains("VIX_ABOVE_THRESHOLD")));
        }

        @Test
        @DisplayName("Should fail threshold rule when VIX <= threshold")
        void shouldFailWhenVixBelowThreshold() throws KiteException, IOException {
            // Current VIX = 11.0, threshold = 12.5
            mockLTP(11.0);
            mockPreviousDayClose(12.0); // Higher than current
            mockFiveMinuteAgoVix(10.99); // Small change

            volatilityFilterService.clearCache();
            VolatilityFilterService.VolatilityFilterResult result =
                    volatilityFilterService.shouldAllowTrade(false);

            // Should still pass because rule 1 might pass if current > prev close
            // Let's verify the threshold rule specifically failed
            assertTrue(result.failedRules().stream()
                    .anyMatch(r -> r.contains("VIX_ABOVE_THRESHOLD: 11.00 <= 12.50")));
        }
    }

    @Nested
    @DisplayName("Rule 3: 5-Minute Percentage Change Tests")
    class FiveMinuteChangeTests {

        @Test
        @DisplayName("Should allow trade when 5-min change > threshold")
        void shouldAllowWhenFiveMinChangeAboveThreshold() throws KiteException, IOException {
            // Current VIX = 11.0, 5 min ago = 10.95 (change = 0.45%)
            // Threshold = 0.3%
            mockLTP(11.0);
            mockPreviousDayClose(12.0); // Higher (rule 1 fails)
            mockFiveMinuteAgoVix(10.95); // 0.45% increase

            volatilityFilterService.clearCache();
            VolatilityFilterService.VolatilityFilterResult result =
                    volatilityFilterService.shouldAllowTrade(false);

            assertTrue(result.allowed());
            assertTrue(result.passedRules().stream()
                    .anyMatch(r -> r.contains("VIX_5MIN_CHANGE")));
        }

        @Test
        @DisplayName("Should fail when 5-min change <= threshold")
        void shouldFailWhenFiveMinChangeBelowThreshold() throws KiteException, IOException {
            // Current VIX = 11.0, 5 min ago = 10.98 (change = 0.18%)
            // Threshold = 0.3%
            mockLTP(11.0);
            mockPreviousDayClose(12.0); // Higher (rule 1 fails)
            mockFiveMinuteAgoVix(10.98); // Only 0.18% increase

            volatilityFilterService.clearCache();
            VolatilityFilterService.VolatilityFilterResult result =
                    volatilityFilterService.shouldAllowTrade(false);

            // VIX is below threshold (11.0 <= 12.5), so rule 2 also fails
            // All rules fail if prev close is higher
            assertTrue(result.failedRules().stream()
                    .anyMatch(r -> r.contains("VIX_5MIN_CHANGE")));
        }
    }

    @Nested
    @DisplayName("All Rules Fail Tests")
    class AllRulesFailTests {

        @Test
        @DisplayName("Should block trade when all rules fail (VIX flat/falling)")
        void shouldBlockWhenAllRulesFail() throws KiteException, IOException {
            // Current VIX = 11.0 (below threshold 12.5)
            // Previous close = 12.0 (higher than current)
            // 5-min ago = 11.0 (no change)
            mockLTP(11.0);
            mockPreviousDayClose(12.0);
            mockFiveMinuteAgoVix(11.0);

            volatilityFilterService.clearCache();
            VolatilityFilterService.VolatilityFilterResult result =
                    volatilityFilterService.shouldAllowTrade(false);

            assertFalse(result.allowed());
            assertTrue(result.reason().contains("VIX flat or falling"));
            assertEquals(0, result.passedRules().size());
            assertEquals(3, result.failedRules().size());
        }
    }

    @Nested
    @DisplayName("Fail-Safe Behavior Tests")
    class FailSafeBehaviorTests {

        @Test
        @DisplayName("Should allow trade when VIX data unavailable and fail-safe is allow")
        void shouldAllowWhenDataUnavailableAndFailSafeAllow() throws KiteException, IOException {
            volatilityConfig.setAllowOnDataUnavailable(true);

            // Mock LTP to throw exception
            when(tradingService.getLTP(any(String[].class))).thenThrow(new IOException("API error"));

            volatilityFilterService.clearCache();
            VolatilityFilterService.VolatilityFilterResult result =
                    volatilityFilterService.shouldAllowTrade(false);

            assertTrue(result.allowed());
            assertTrue(result.reason().contains("VIX data unavailable"));
        }

        @Test
        @DisplayName("Should block trade when VIX data unavailable and fail-safe is block")
        void shouldBlockWhenDataUnavailableAndFailSafeBlock() throws KiteException, IOException {
            volatilityConfig.setAllowOnDataUnavailable(false);

            // Mock LTP to throw exception
            when(tradingService.getLTP(any(String[].class))).thenThrow(new IOException("API error"));

            volatilityFilterService.clearCache();
            VolatilityFilterService.VolatilityFilterResult result =
                    volatilityFilterService.shouldAllowTrade(false);

            assertFalse(result.allowed());
            assertTrue(result.reason().contains("VIX data unavailable"));
        }
    }

    @Nested
    @DisplayName("Caching Tests")
    class CachingTests {

        @Test
        @DisplayName("Should use cached data within TTL")
        void shouldUseCachedDataWithinTtl() throws KiteException, IOException {
            mockLTP(14.0);
            mockPreviousDayClose(12.0);
            mockFiveMinuteAgoVix(13.5);

            // First call - fetches from API
            volatilityFilterService.clearCache();
            volatilityFilterService.shouldAllowTrade(false);

            // Second call - should use cache
            volatilityFilterService.shouldAllowTrade(false);

            // LTP should be called only once (for the first fetch)
            verify(tradingService, times(1)).getLTP(any(String[].class));
        }

        @Test
        @DisplayName("Should clear cache when requested")
        void shouldClearCache() throws KiteException, IOException {
            mockLTP(14.0);
            mockPreviousDayClose(12.0);
            mockFiveMinuteAgoVix(13.5);

            // First call
            volatilityFilterService.clearCache();
            volatilityFilterService.shouldAllowTrade(false);

            // Clear and call again
            volatilityFilterService.clearCache();
            volatilityFilterService.shouldAllowTrade(false);

            // LTP should be called twice (once for each fetch after clear)
            verify(tradingService, times(2)).getLTP(any(String[].class));
        }
    }

    // ==================== Helper Methods ====================

    private void mockLTP(double vixValue) throws KiteException, IOException {
        Map<String, LTPQuote> ltpMap = new HashMap<>();
        LTPQuote quote = new LTPQuote();
        quote.lastPrice = vixValue;
        ltpMap.put(VIX_SYMBOL, quote);

        when(tradingService.getLTP(any(String[].class))).thenReturn(ltpMap);
    }

    private void mockPreviousDayClose(double closeValue) throws KiteException, IOException {
        HistoricalData historicalData = new HistoricalData();
        historicalData.dataArrayList = new ArrayList<>();

        HistoricalData candle = new HistoricalData();
        candle.close = closeValue;
        historicalData.dataArrayList.add(candle);

        // Mock for daily interval (previous day close)
        when(tradingService.getHistoricalData(any(Date.class), any(Date.class), eq(VIX_TOKEN),
                eq("day"), eq(false), eq(false))).thenReturn(historicalData);
    }

    private void mockFiveMinuteAgoVix(double vixValue) throws KiteException, IOException {
        HistoricalData historicalData = new HistoricalData();
        historicalData.dataArrayList = new ArrayList<>();

        // Add two candles - the second-to-last is the 5-min ago value
        HistoricalData fiveMinAgoCandle = new HistoricalData();
        fiveMinAgoCandle.close = vixValue;
        fiveMinAgoCandle.open = vixValue;
        historicalData.dataArrayList.add(fiveMinAgoCandle);

        HistoricalData currentCandle = new HistoricalData();
        currentCandle.close = vixValue + 0.05; // Slight difference for current
        historicalData.dataArrayList.add(currentCandle);

        // Mock for 5-minute interval
        when(tradingService.getHistoricalData(any(Date.class), any(Date.class), eq(VIX_TOKEN),
                eq("5minute"), eq(false), eq(false))).thenReturn(historicalData);
    }
}

