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
        volatilityConfig.setSpikeThresholdPct(new BigDecimal("1.0"));
        volatilityConfig.setMaxVixAbovePrevClosePct(new BigDecimal("5.0"));
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
    @DisplayName("AND-based Rules: All Must Pass for Seller")
    class AndBasedRulesTests {

        @Test
        @DisplayName("Should allow when VIX >= threshold, not spiking, not escalating (all rules pass)")
        void shouldAllowWhenAllRulesPass() throws KiteException, IOException {
            // VIX = 14.0 (above 12.5), prev close = 13.5 (VIX only ~3.7% above = below 5%)
            // 5-min ago = 13.9 (change = ~0.7% = below 1.0%)
            mockLTP(14.0);
            mockPreviousDayClose(13.5);
            mockFiveMinuteAgoVix(13.9);

            volatilityFilterService.clearCache();
            VolatilityFilterService.VolatilityFilterResult result =
                    volatilityFilterService.shouldAllowTrade(false);

            assertTrue(result.allowed(), "All rules pass: VIX above min, not spiking, not escalating");
            assertTrue(result.failedRules().isEmpty());
        }

        @Test
        @DisplayName("Should block when VIX below minimum threshold (insufficient premium)")
        void shouldBlockWhenVixBelowMinimum() throws KiteException, IOException {
            // VIX = 11.0 (below 12.5)
            mockLTP(11.0);
            mockPreviousDayClose(10.5);
            mockFiveMinuteAgoVix(10.9);

            volatilityFilterService.clearCache();
            VolatilityFilterService.VolatilityFilterResult result =
                    volatilityFilterService.shouldAllowTrade(false);

            assertFalse(result.allowed(), "VIX below minimum should block");
            assertTrue(result.failedRules().stream()
                    .anyMatch(r -> r.contains("VIX_BELOW_MIN")));
        }

        @Test
        @DisplayName("Should block when VIX is spiking (5-min change > spike threshold)")
        void shouldBlockWhenVixSpiking() throws KiteException, IOException {
            // VIX = 15.0, 5-min ago = 14.0 (change = ~7.14% >> 1.0% spike threshold)
            mockLTP(15.0);
            mockPreviousDayClose(14.5);
            mockFiveMinuteAgoVix(14.0);

            volatilityFilterService.clearCache();
            VolatilityFilterService.VolatilityFilterResult result =
                    volatilityFilterService.shouldAllowTrade(false);

            assertFalse(result.allowed(), "VIX spiking should block");
            assertTrue(result.failedRules().stream()
                    .anyMatch(r -> r.contains("VIX_SPIKING")));
        }

        @Test
        @DisplayName("Should block when VIX escalating above previous close (> max threshold)")
        void shouldBlockWhenVixEscalating() throws KiteException, IOException {
            // VIX = 15.0, prev close = 13.0 (15.38% above = exceeds 5%)
            // 5-min ago = 14.9 (small change, not spiking)
            mockLTP(15.0);
            mockPreviousDayClose(13.0);
            mockFiveMinuteAgoVix(14.9);

            volatilityFilterService.clearCache();
            VolatilityFilterService.VolatilityFilterResult result =
                    volatilityFilterService.shouldAllowTrade(false);

            assertFalse(result.allowed(), "VIX escalating above prev close should block");
            assertTrue(result.failedRules().stream()
                    .anyMatch(r -> r.contains("VIX_ESCALATING")));
        }

        @Test
        @DisplayName("Should allow when 5-min data unavailable (fail-safe: pass)")
        void shouldAllowWhenFiveMinDataUnavailable() throws KiteException, IOException {
            // VIX = 14.0 (above threshold), prev close = 13.5 (within 5%)
            // But no 5-min data available
            mockLTP(14.0);
            mockPreviousDayClose(13.5);
            // Mock 5-minute to return empty data
            HistoricalData emptyData = new HistoricalData();
            emptyData.dataArrayList = new ArrayList<>();
            when(tradingService.getHistoricalData(any(Date.class), any(Date.class), eq(VIX_TOKEN),
                    eq("5minute"), eq(false), eq(false))).thenReturn(emptyData);

            volatilityFilterService.clearCache();
            VolatilityFilterService.VolatilityFilterResult result =
                    volatilityFilterService.shouldAllowTrade(false);

            assertTrue(result.allowed(), "Should allow when 5-min data unavailable (fail-safe)");
        }

        @Test
        @DisplayName("Should block when ALL rules fail (VIX too low + spiking + escalating)")
        void shouldBlockWhenAllRulesFail() throws KiteException, IOException {
            // VIX = 11.0 (below 12.5), prev close = 9.0 (22% above), 5min ago = 10.0 (10% spike)
            mockLTP(11.0);
            mockPreviousDayClose(9.0);
            mockFiveMinuteAgoVix(10.0);

            volatilityFilterService.clearCache();
            VolatilityFilterService.VolatilityFilterResult result =
                    volatilityFilterService.shouldAllowTrade(false);

            assertFalse(result.allowed());
            assertTrue(result.reason().contains("unfavorable for selling"));
            assertTrue(result.failedRules().size() >= 2); // At least VIX_BELOW_MIN + VIX_SPIKING
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

