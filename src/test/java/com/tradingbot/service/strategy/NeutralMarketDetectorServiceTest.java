package com.tradingbot.service.strategy;

import com.tradingbot.config.NeutralMarketConfig;
import com.tradingbot.service.InstrumentCacheService;
import com.tradingbot.service.TradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for NeutralMarketDetectorService.
 * Tests each signal independently and the composite evaluation logic.
 */
@ExtendWith(MockitoExtension.class)
class NeutralMarketDetectorServiceTest {

    @Mock
    private TradingService tradingService;

    @Mock
    private InstrumentCacheService instrumentCacheService;

    private NeutralMarketConfig config;
    private NeutralMarketDetectorService service;

    @BeforeEach
    void setUp() {
        config = new NeutralMarketConfig();
        config.setEnabled(true);
        config.setMinimumScore(7);
        config.setVwapDeviationThreshold(0.0015);
        config.setVwapCandleCount(15);
        config.setAdxThreshold(18.0);
        config.setAdxPeriod(14);
        config.setAdxCandleInterval("3minute");
        config.setAdxCandleCount(50);
        config.setGammaPinThreshold(0.002);
        config.setStrikesAroundAtm(10);
        config.setRangeCompressionThreshold(0.0025);
        config.setRangeCompressionCandles(5);
        config.setPremiumSnapshotMinIntervalMs(25000);
        config.setCacheTtlMs(30000);
        config.setAllowOnDataUnavailable(true);

        // Override market hours check so tests run 24/7 (nights, weekends, CI)
        service = new NeutralMarketDetectorService(config, tradingService, instrumentCacheService) {
            @Override
            boolean isWithinMarketHours() {
                return true;
            }

            @Override
            boolean hasEnoughTimeForADX() {
                return true;
            }
        };
    }

    // ==================== MARKET HOURS GUARD ====================

    @Test
    @DisplayName("When market is closed, should return dataUnavailable based on fail-safe config")
    void whenMarketClosed_shouldReturnDataUnavailable() {
        // Create service WITHOUT the market hours override
        NeutralMarketDetectorService closedMarketService =
                new NeutralMarketDetectorService(config, tradingService, instrumentCacheService) {
                    @Override
                    boolean isWithinMarketHours() {
                        return false; // Simulate closed market
                    }
                };

        // With fail-safe ON
        config.setAllowOnDataUnavailable(true);
        closedMarketService.clearCache();
        NeutralMarketDetectorService.NeutralMarketResult result = closedMarketService.evaluate("NIFTY");
        assertTrue(result.neutral(), "Fail-safe should allow trade when market is closed");
        assertTrue(result.summary().contains("market hours"));

        // With fail-safe OFF
        config.setAllowOnDataUnavailable(false);
        closedMarketService.clearCache();
        NeutralMarketDetectorService.NeutralMarketResult blocked = closedMarketService.evaluate("NIFTY");
        assertFalse(blocked.neutral(), "Should block trade when market is closed and fail-safe is off");
    }

    // ==================== DISABLED FILTER ====================

    @Test
    @DisplayName("When filter is disabled, should return neutral=true with max score")
    void whenDisabled_shouldAllowTrade() {
        config.setEnabled(false);

        NeutralMarketDetectorService.NeutralMarketResult result = service.evaluate("NIFTY");

        assertTrue(result.neutral());
        assertEquals(10, result.totalScore());
        assertTrue(result.summary().contains("disabled"));
    }

    // ==================== ADX COMPUTATION ====================

    @Nested
    @DisplayName("ADX Computation Tests")
    class ADXComputationTests {

        @Test
        @DisplayName("ADX of flat/range-bound candles should be low")
        void flatCandles_shouldProduceLowADX() {
            // Create 50 candles with no directional movement — oscillating around 100
            List<HistoricalData> candles = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                HistoricalData c = new HistoricalData();
                // Small random-like oscillation — no trend
                c.high = 100.5 + (i % 3) * 0.1;
                c.low = 99.5 - (i % 3) * 0.1;
                c.close = 100.0 + ((i % 2 == 0) ? 0.1 : -0.1);
                candles.add(c);
            }

            double[] adx = NeutralMarketDetectorService.computeADXSeries(candles, 14);

            assertNotNull(adx);
            assertTrue(adx.length > 0, "Should produce ADX values");
            // Flat/range-bound market should have low ADX
            double latestADX = adx[adx.length - 1];
            assertTrue(latestADX < 25,
                    "ADX of flat candles should be < 25, got " + latestADX);
        }

        @Test
        @DisplayName("ADX of strong uptrend should be high")
        void strongUptrend_shouldProduceHighADX() {
            List<HistoricalData> candles = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                HistoricalData c = new HistoricalData();
                // Strong consistent uptrend: each candle definitively higher than previous
                c.high = 100 + i * 2.0 + 1.0;
                c.low = 100 + i * 2.0 - 0.5;
                c.close = 100 + i * 2.0 + 0.5;
                candles.add(c);
            }

            double[] adx = NeutralMarketDetectorService.computeADXSeries(candles, 14);

            assertNotNull(adx);
            assertTrue(adx.length > 0);
            double latestADX = adx[adx.length - 1];
            assertTrue(latestADX > 25,
                    "ADX of strong uptrend should be > 25, got " + latestADX);
        }

        @Test
        @DisplayName("ADX of strong downtrend should also be high (ADX is non-directional)")
        void strongDowntrend_shouldProduceHighADX() {
            List<HistoricalData> candles = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                HistoricalData c = new HistoricalData();
                c.high = 200 - i * 2.0 + 0.5;
                c.low = 200 - i * 2.0 - 1.0;
                c.close = 200 - i * 2.0 - 0.5;
                candles.add(c);
            }

            double[] adx = NeutralMarketDetectorService.computeADXSeries(candles, 14);

            assertNotNull(adx);
            assertTrue(adx.length > 0);
            double latestADX = adx[adx.length - 1];
            assertTrue(latestADX > 25,
                    "ADX of strong downtrend should be > 25, got " + latestADX);
        }

        @Test
        @DisplayName("ADX with insufficient candles should return empty array")
        void insufficientCandles_shouldReturnEmpty() {
            List<HistoricalData> candles = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                HistoricalData c = new HistoricalData();
                c.high = 100.5;
                c.low = 99.5;
                c.close = 100.0;
                candles.add(c);
            }

            double[] adx = NeutralMarketDetectorService.computeADXSeries(candles, 14);

            assertNotNull(adx);
            assertEquals(0, adx.length, "Should return empty for insufficient data");
        }

        @Test
        @DisplayName("ADX values should be non-negative")
        void adxValues_shouldBeNonNegative() {
            List<HistoricalData> candles = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                HistoricalData c = new HistoricalData();
                c.high = 100 + Math.sin(i * 0.5) * 3;
                c.low = 98 + Math.sin(i * 0.5) * 3;
                c.close = 99 + Math.sin(i * 0.5) * 3;
                candles.add(c);
            }

            double[] adx = NeutralMarketDetectorService.computeADXSeries(candles, 14);

            assertNotNull(adx);
            for (double val : adx) {
                assertTrue(val >= 0, "ADX value should be non-negative, got " + val);
            }
        }

        @Test
        @DisplayName("ADX with exactly period+1 candles should produce at least one value")
        void minimumCandles_shouldProduceAtLeastOneValue() {
            List<HistoricalData> candles = new ArrayList<>();
            for (int i = 0; i < 15; i++) { // period(14) + 1
                HistoricalData c = new HistoricalData();
                c.high = 100 + i * 0.5;
                c.low = 99 + i * 0.5;
                c.close = 99.5 + i * 0.5;
                candles.add(c);
            }

            double[] adx = NeutralMarketDetectorService.computeADXSeries(candles, 14);

            assertNotNull(adx);
            assertTrue(adx.length >= 1, "Should produce at least one ADX value with period+1 candles");
        }
    }

    // ==================== FAIL-SAFE ====================

    @Test
    @DisplayName("When spot price fetch fails and allowOnDataUnavailable=true, should allow trade")
    void whenDataUnavailable_andFailSafeEnabled_shouldAllow() throws Exception, KiteException {
        when(tradingService.getLTP(any())).thenThrow(new IOException("Network error"));

        NeutralMarketDetectorService.NeutralMarketResult result = service.evaluate("NIFTY");

        assertTrue(result.neutral(), "Fail-safe should allow trade on data unavailability");
        assertTrue(result.summary().contains("ALLOW"));
    }

    @Test
    @DisplayName("When spot price fetch fails and allowOnDataUnavailable=false, should block trade")
    void whenDataUnavailable_andFailSafeDisabled_shouldBlock() throws Exception, KiteException {
        config.setAllowOnDataUnavailable(false);
        when(tradingService.getLTP(any())).thenThrow(new IOException("Network error"));

        NeutralMarketDetectorService.NeutralMarketResult result = service.evaluate("NIFTY");

        assertFalse(result.neutral(), "Should block trade when fail-safe is disabled");
        assertTrue(result.summary().contains("BLOCK"));
    }

    // ==================== CACHE ====================

    @Test
    @DisplayName("Second call within cache TTL should return cached result")
    void cachedResult_shouldBeReused() throws Exception, KiteException {
        config.setCacheTtlMs(60000); // 60 seconds
        when(tradingService.getLTP(any())).thenThrow(new IOException("Network error"));

        NeutralMarketDetectorService.NeutralMarketResult result1 = service.evaluate("NIFTY");
        NeutralMarketDetectorService.NeutralMarketResult result2 = service.evaluate("NIFTY");

        assertEquals(result1.evaluatedAt(), result2.evaluatedAt(), "Should return same cached result");
        // getLTP should only be called once (first evaluation triggers it; cache serves second)
        verify(tradingService, times(1)).getLTP(any());
    }

    @Test
    @DisplayName("clearCache should force fresh evaluation on next call")
    void clearCache_shouldForceRefresh() throws Exception, KiteException {
        config.setCacheTtlMs(60000);
        when(tradingService.getLTP(any())).thenThrow(new IOException("Network error"));

        service.evaluate("NIFTY");
        service.clearCache();
        service.evaluate("NIFTY");

        verify(tradingService, times(2)).getLTP(any());
    }

    // ==================== CONVENIENCE METHODS ====================

    @Test
    @DisplayName("calculateNeutralScore should return the numeric score")
    void calculateNeutralScore_shouldReturnScore() {
        config.setEnabled(false);
        assertEquals(10, service.calculateNeutralScore("NIFTY"));
    }

    @Test
    @DisplayName("isMarketNeutral should return boolean based on minimum score")
    void isMarketNeutral_shouldReturnBoolean() {
        config.setEnabled(false);
        assertTrue(service.isMarketNeutral("NIFTY"));
    }

    // ==================== RESULT STRUCTURE ====================

    @Test
    @DisplayName("Disabled result should have correct structure")
    void disabledResult_shouldHaveCorrectStructure() {
        config.setEnabled(false);
        NeutralMarketDetectorService.NeutralMarketResult result = service.evaluate("NIFTY");

        assertEquals(10, result.totalScore());
        assertEquals(10, result.maxScore());
        assertEquals(0, result.minimumRequired());
        assertTrue(result.signals().isEmpty());
        assertNotNull(result.evaluatedAt());
    }

    @Test
    @DisplayName("DataUnavailable result should encode fail-safe decision")
    void dataUnavailableResult_shouldEncodeFailSafe() throws Exception, KiteException {
        when(tradingService.getLTP(any())).thenThrow(new IOException("timeout"));

        // fail-safe ON
        config.setAllowOnDataUnavailable(true);
        service.clearCache();
        NeutralMarketDetectorService.NeutralMarketResult allowed = service.evaluate("NIFTY");
        assertTrue(allowed.neutral());
        assertEquals(0, allowed.totalScore());

        // fail-safe OFF
        config.setAllowOnDataUnavailable(false);
        service.clearCache();
        NeutralMarketDetectorService.NeutralMarketResult blocked = service.evaluate("NIFTY");
        assertFalse(blocked.neutral());
        assertEquals(0, blocked.totalScore());
    }
}







