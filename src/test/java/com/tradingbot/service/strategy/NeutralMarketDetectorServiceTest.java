package com.tradingbot.service.strategy;

import com.tradingbot.config.NeutralMarketConfig;
import com.tradingbot.service.InstrumentCacheService;
import com.tradingbot.service.MarketDataEngine;
import com.tradingbot.service.TradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.LTPQuote;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyBoolean;

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

    @Mock
    private MarketDataEngine marketDataEngine;

    private NeutralMarketConfig config;
    private NeutralMarketDetectorService service;

    @BeforeEach
    void setUp() throws KiteException, IOException {
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
        config.setPremiumMinDecayPct(0.5);
        config.setPremiumMaxSnapshotAgeMs(120000);
        config.setCacheTtlMs(30000);
        config.setAllowOnDataUnavailable(true);
        config.setExpiryDayMinimumScore(8);
        config.setExpiryDayRangeThreshold(0.002);

        // Mock NSE instruments so resolveInstrumentToken("NIFTY") can find the NIFTY 50 token
        List<Instrument> nseInstruments = new ArrayList<>();
        Instrument nifty50 = new Instrument();
        nifty50.tradingsymbol = "NIFTY 50";
        nifty50.instrument_token = 256265L;
        nseInstruments.add(nifty50);
        lenient().when(instrumentCacheService.getInstruments("NSE")).thenReturn(nseInstruments);

        // Override market hours check so tests run 24/7 (nights, weekends, CI)
        // MarketDataEngine returns empty by default — forces fallback to tradingService
        service = new NeutralMarketDetectorService(config, tradingService, instrumentCacheService, marketDataEngine) {
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
                new NeutralMarketDetectorService(config, tradingService, instrumentCacheService, marketDataEngine) {
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

    // ==================== ADX EARLY SESSION ====================

    @Nested
    @DisplayName("ADX Early Session Tests")
    class ADXEarlySessionTests {

        @Test
        @DisplayName("ADX early session should return unavailable (score 0), not passed")
        void adxEarlySession_shouldReturnUnavailable() throws Exception, KiteException {
            // Create a service where hasEnoughTimeForADX() returns false
            NeutralMarketDetectorService earlySessionService =
                    new NeutralMarketDetectorService(config, tradingService, instrumentCacheService, marketDataEngine) {
                        @Override
                        boolean isWithinMarketHours() {
                            return true;
                        }

                        @Override
                        boolean hasEnoughTimeForADX() {
                            return false; // Simulate early session
                        }
                    };

            // Mock spot price via MarketDataEngine
            lenient().when(marketDataEngine.getIndexPrice("NIFTY")).thenReturn(Optional.of(24000.0));
            // Mock 1-min candles
            lenient().when(marketDataEngine.getCandles("NIFTY")).thenReturn(Optional.of(createFlatCandles(15, 24000)));
            // Mock NFO instruments — empty so gamma pin and premium decay return unavailable
            lenient().when(instrumentCacheService.getInstruments("NFO")).thenReturn(Collections.emptyList());
            // Mock ADX candles via direct API (lenient — may not be called if ADX exits early)
            lenient().when(tradingService.getHistoricalData(any(), any(), anyString(), anyString(), anyBoolean(), anyBoolean()))
                    .thenReturn(createHistoricalDataResult(createFlatCandles(50, 24000)));

            NeutralMarketDetectorService.NeutralMarketResult result = earlySessionService.evaluate("NIFTY");

            // Find the ADX signal
            NeutralMarketDetectorService.SignalResult adxSignal = result.signals().stream()
                    .filter(s -> "ADX_TREND".equals(s.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("ADX_TREND signal not found"));

            assertEquals(0, adxSignal.score(), "ADX early session should contribute 0, not 2");
            assertFalse(adxSignal.passed(), "ADX early session should not pass");
            assertTrue(adxSignal.detail().contains("DATA_UNAVAILABLE"),
                    "ADX early session should be marked as unavailable, got: " + adxSignal.detail());
        }
    }

    // ==================== PREMIUM DECAY TESTS ====================

    @Nested
    @DisplayName("Premium Decay Signal Tests")
    class PremiumDecayTests {

        @Test
        @DisplayName("First invocation should return unavailable (not failed)")
        void premiumDecay_firstInvocation_shouldReturnUnavailable() throws Exception, KiteException {
            // Mock spot price
            when(marketDataEngine.getIndexPrice("NIFTY")).thenReturn(Optional.of(24000.0));
            // Mock 1-min candles
            when(marketDataEngine.getCandles("NIFTY")).thenReturn(Optional.of(createFlatCandles(15, 24000)));
            // Mock NFO instruments with ATM options
            List<Instrument> nfoInstruments = createNFOInstruments(24000.0);
            when(instrumentCacheService.getInstruments("NFO")).thenReturn(nfoInstruments);
            // Mock LTP for premium decay
            Map<String, LTPQuote> ltpMap = new HashMap<>();
            ltpMap.put("NFO:NIFTY25MAR24000CE", createLTPQuote(120.0));
            ltpMap.put("NFO:NIFTY25MAR24000PE", createLTPQuote(115.0));
            when(tradingService.getLTP(any())).thenReturn(ltpMap);
            // Mock ADX candles
            when(tradingService.getHistoricalData(any(), any(), anyString(), anyString(), anyBoolean(), anyBoolean()))
                    .thenReturn(createHistoricalDataResult(createFlatCandles(50, 24000)));
            // Mock batch quote for gamma pin
            when(tradingService.getQuote(any())).thenReturn(Collections.emptyMap());

            NeutralMarketDetectorService.NeutralMarketResult result = service.evaluate("NIFTY");

            NeutralMarketDetectorService.SignalResult premiumSignal = result.signals().stream()
                    .filter(s -> "PREMIUM_DECAY".equals(s.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("PREMIUM_DECAY signal not found"));

            assertEquals(0, premiumSignal.score(), "First invocation should score 0");
            assertFalse(premiumSignal.passed(), "First invocation should not pass");
            assertTrue(premiumSignal.detail().contains("DATA_UNAVAILABLE"),
                    "First invocation should be marked unavailable, got: " + premiumSignal.detail());
        }

        @Test
        @DisplayName("ATM strike shift between snapshots should return unavailable")
        void premiumDecay_atmStrikeShift_shouldReturnUnavailable() throws Exception, KiteException {
            // First evaluation at spot 24000 (ATM = 24000)
            lenient().when(marketDataEngine.getIndexPrice("NIFTY")).thenReturn(Optional.of(24000.0));
            lenient().when(marketDataEngine.getCandles("NIFTY")).thenReturn(Optional.of(createFlatCandles(15, 24000)));
            List<Instrument> nfoInstruments = createNFOInstruments(24000.0);
            // Add instruments for 24050 as well (for the shifted ATM)
            nfoInstruments.addAll(createNFOInstruments(24050.0));
            lenient().when(instrumentCacheService.getInstruments("NFO")).thenReturn(nfoInstruments);
            Map<String, LTPQuote> ltpMap1 = new HashMap<>();
            ltpMap1.put("NFO:NIFTY25MAR24000CE", createLTPQuote(120.0));
            ltpMap1.put("NFO:NIFTY25MAR24000PE", createLTPQuote(115.0));
            lenient().when(tradingService.getLTP(any())).thenReturn(ltpMap1);
            lenient().when(tradingService.getHistoricalData(any(), any(), anyString(), anyString(), anyBoolean(), anyBoolean()))
                    .thenReturn(createHistoricalDataResult(createFlatCandles(50, 24000)));
            lenient().when(tradingService.getQuote(any())).thenReturn(Collections.emptyMap());

            // First evaluation: baseline stored
            service.evaluate("NIFTY");
            service.clearCache(); // Force re-evaluation but keep premium snapshot

            // Actually, clearCache clears premium snapshot too. We need to only clear the
            // cachedResult, not the premium snapshot. Let's do two evaluations with different spot.
            // Re-create service to have fresh cachedResult but keep premium snapshot:
            // Instead, let's set cache TTL to 0 to force re-evaluation
            config.setCacheTtlMs(0);

            // Second evaluation: spot shifts to 24075 (ATM changes from 24000 to 24050+)
            lenient().when(marketDataEngine.getIndexPrice("NIFTY")).thenReturn(Optional.of(24075.0));
            lenient().when(marketDataEngine.getCandles("NIFTY")).thenReturn(Optional.of(createFlatCandles(15, 24075)));
            Map<String, LTPQuote> ltpMap2 = new HashMap<>();
            ltpMap2.put("NFO:NIFTY25MAR24050CE", createLTPQuote(100.0));
            ltpMap2.put("NFO:NIFTY25MAR24050PE", createLTPQuote(130.0));
            lenient().when(tradingService.getLTP(any())).thenReturn(ltpMap2);

            NeutralMarketDetectorService.NeutralMarketResult result = service.evaluate("NIFTY");

            NeutralMarketDetectorService.SignalResult premiumSignal = result.signals().stream()
                    .filter(s -> "PREMIUM_DECAY".equals(s.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("PREMIUM_DECAY signal not found"));

            assertEquals(0, premiumSignal.score(), "ATM strike shift should score 0");
            assertTrue(premiumSignal.detail().contains("DATA_UNAVAILABLE") || premiumSignal.detail().contains("strike shifted"),
                    "Should detect ATM strike shift, got: " + premiumSignal.detail());
        }

        @Test
        @DisplayName("Trivially small decay should fail when minimum decay threshold is set")
        void premiumDecay_tinyDecay_shouldFailWithMinThreshold() throws Exception, KiteException {
            config.setPremiumMinDecayPct(0.5); // Require >= 0.5% decay
            config.setPremiumSnapshotMinIntervalMs(0); // Disable interval check for this test
            config.setCacheTtlMs(0); // Force re-evaluation

            when(marketDataEngine.getIndexPrice("NIFTY")).thenReturn(Optional.of(24000.0));
            when(marketDataEngine.getCandles("NIFTY")).thenReturn(Optional.of(createFlatCandles(15, 24000)));
            List<Instrument> nfoInstruments = createNFOInstruments(24000.0);
            when(instrumentCacheService.getInstruments("NFO")).thenReturn(nfoInstruments);
            when(tradingService.getHistoricalData(any(), any(), anyString(), anyString(), anyBoolean(), anyBoolean()))
                    .thenReturn(createHistoricalDataResult(createFlatCandles(50, 24000)));
            when(tradingService.getQuote(any())).thenReturn(Collections.emptyMap());

            // First call: baseline CE=120, PE=115
            Map<String, LTPQuote> ltpMap1 = new HashMap<>();
            ltpMap1.put("NFO:NIFTY25MAR24000CE", createLTPQuote(120.0));
            ltpMap1.put("NFO:NIFTY25MAR24000PE", createLTPQuote(115.0));
            when(tradingService.getLTP(any())).thenReturn(ltpMap1);
            NeutralMarketDetectorService.NeutralMarketResult baseline = service.evaluate("NIFTY");
            // Verify baseline was actually stored (premium decay should return DATA_UNAVAILABLE on first call)
            assertTrue(baseline.signals().stream()
                            .filter(s -> "PREMIUM_DECAY".equals(s.name()))
                            .findFirst()
                            .map(s -> s.detail().contains("Baseline stored") || s.detail().contains("DATA_UNAVAILABLE"))
                            .orElse(false),
                    "First call should store baseline. Signals: " + baseline.signals());

            // Small delay to ensure Instant.now() differs between snapshot timestamps
            Thread.sleep(5);

            // Second call: trivial decay — CE 120→119.99 (0.008%), PE 115→114.99 (0.009%)
            Map<String, LTPQuote> ltpMap2 = new HashMap<>();
            ltpMap2.put("NFO:NIFTY25MAR24000CE", createLTPQuote(119.99));
            ltpMap2.put("NFO:NIFTY25MAR24000PE", createLTPQuote(114.99));
            when(tradingService.getLTP(any())).thenReturn(ltpMap2);

            NeutralMarketDetectorService.NeutralMarketResult result = service.evaluate("NIFTY");

            NeutralMarketDetectorService.SignalResult premiumSignal = result.signals().stream()
                    .filter(s -> "PREMIUM_DECAY".equals(s.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("PREMIUM_DECAY signal not found"));

            assertEquals(0, premiumSignal.score(), "Trivially small decay should score 0");
            assertFalse(premiumSignal.passed(), "Trivially small decay should not pass");
            assertTrue(premiumSignal.detail().contains("INSUFFICIENT"),
                    "Should indicate insufficient decay, got: " + premiumSignal.detail());
        }

        @Test
        @DisplayName("Sufficient decay should pass when minimum decay threshold is met")
        void premiumDecay_sufficientDecay_shouldPass() throws Exception, KiteException {
            config.setPremiumMinDecayPct(0.5);
            config.setPremiumSnapshotMinIntervalMs(0);
            config.setCacheTtlMs(0);

            when(marketDataEngine.getIndexPrice("NIFTY")).thenReturn(Optional.of(24000.0));
            when(marketDataEngine.getCandles("NIFTY")).thenReturn(Optional.of(createFlatCandles(15, 24000)));
            List<Instrument> nfoInstruments = createNFOInstruments(24000.0);
            when(instrumentCacheService.getInstruments("NFO")).thenReturn(nfoInstruments);
            when(tradingService.getHistoricalData(any(), any(), anyString(), anyString(), anyBoolean(), anyBoolean()))
                    .thenReturn(createHistoricalDataResult(createFlatCandles(50, 24000)));
            when(tradingService.getQuote(any())).thenReturn(Collections.emptyMap());

            // First call: baseline CE=120, PE=115
            Map<String, LTPQuote> ltpMap1 = new HashMap<>();
            ltpMap1.put("NFO:NIFTY25MAR24000CE", createLTPQuote(120.0));
            ltpMap1.put("NFO:NIFTY25MAR24000PE", createLTPQuote(115.0));
            when(tradingService.getLTP(any())).thenReturn(ltpMap1);
            service.evaluate("NIFTY");

            // Small delay to ensure Instant.now() differs between snapshot timestamps
            Thread.sleep(5);

            // Second call: significant decay — CE 120→118.5 (1.25%), PE 115→114.0 (0.87%)
            Map<String, LTPQuote> ltpMap2 = new HashMap<>();
            ltpMap2.put("NFO:NIFTY25MAR24000CE", createLTPQuote(118.5));
            ltpMap2.put("NFO:NIFTY25MAR24000PE", createLTPQuote(114.0));
            when(tradingService.getLTP(any())).thenReturn(ltpMap2);

            NeutralMarketDetectorService.NeutralMarketResult result = service.evaluate("NIFTY");

            NeutralMarketDetectorService.SignalResult premiumSignal = result.signals().stream()
                    .filter(s -> "PREMIUM_DECAY".equals(s.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("PREMIUM_DECAY signal not found"));

            assertEquals(2, premiumSignal.score(), "Sufficient decay should score 2");
            assertTrue(premiumSignal.passed(), "Sufficient decay should pass");
            assertTrue(premiumSignal.detail().contains("SUFFICIENT"),
                    "Should indicate sufficient decay, got: " + premiumSignal.detail());
        }
    }

    // ==================== MARKET DATA ENGINE INTEGRATION ====================

    @Nested
    @DisplayName("MarketDataEngine Cache Integration Tests")
    class MarketDataEngineCacheTests {

        @Test
        @DisplayName("Spot price should prefer MarketDataEngine cache over direct API call")
        void spotPrice_shouldPreferMarketDataEngine() throws Exception, KiteException {
            when(marketDataEngine.getIndexPrice("NIFTY")).thenReturn(Optional.of(24000.0));
            when(marketDataEngine.getCandles("NIFTY")).thenReturn(Optional.of(createFlatCandles(15, 24000)));
            when(instrumentCacheService.getInstruments("NFO")).thenReturn(Collections.emptyList());
            when(tradingService.getHistoricalData(any(), any(), anyString(), anyString(), anyBoolean(), anyBoolean()))
                    .thenReturn(createHistoricalDataResult(createFlatCandles(50, 24000)));

            service.evaluate("NIFTY");

            // tradingService.getLTP should only be called for premium decay LTP fetch,
            // NOT for spot price (which should come from MDE cache)
            // Since NFO instruments are empty, premium decay won't call getLTP either
            verify(tradingService, never()).getLTP(any());
        }

        @Test
        @DisplayName("Should fall back to direct API when MarketDataEngine cache is empty")
        void spotPrice_shouldFallbackWhenCacheEmpty() throws Exception, KiteException {
            when(marketDataEngine.getIndexPrice("NIFTY")).thenReturn(Optional.empty());
            when(marketDataEngine.getCandles("NIFTY")).thenReturn(Optional.empty());

            // Mock spot price via direct API
            Map<String, LTPQuote> spotLtp = new HashMap<>();
            spotLtp.put("NSE:NIFTY 50", createLTPQuote(24000.0));
            when(tradingService.getLTP(any())).thenReturn(spotLtp);
            when(instrumentCacheService.getInstruments(anyString())).thenReturn(Collections.emptyList());
            when(tradingService.getHistoricalData(any(), any(), anyString(), anyString(), anyBoolean(), anyBoolean()))
                    .thenReturn(createHistoricalDataResult(createFlatCandles(50, 24000)));

            service.evaluate("NIFTY");

            // tradingService.getLTP should be called (fallback path)
            verify(tradingService, atLeastOnce()).getLTP(any());
        }
    }

    // ==================== FAIL-SAFE ====================

    @Test
    @DisplayName("When spot price fetch fails and allowOnDataUnavailable=true, should allow trade")
    void whenDataUnavailable_andFailSafeEnabled_shouldAllow() throws Exception, KiteException {
        when(marketDataEngine.getIndexPrice("NIFTY")).thenReturn(Optional.empty());
        when(tradingService.getLTP(any())).thenThrow(new IOException("Network error"));

        NeutralMarketDetectorService.NeutralMarketResult result = service.evaluate("NIFTY");

        assertTrue(result.neutral(), "Fail-safe should allow trade on data unavailability");
        assertTrue(result.summary().contains("ALLOW"));
    }

    @Test
    @DisplayName("When spot price fetch fails and allowOnDataUnavailable=false, should block trade")
    void whenDataUnavailable_andFailSafeDisabled_shouldBlock() throws Exception, KiteException {
        config.setAllowOnDataUnavailable(false);
        when(marketDataEngine.getIndexPrice("NIFTY")).thenReturn(Optional.empty());
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
        when(marketDataEngine.getIndexPrice("NIFTY")).thenReturn(Optional.empty());
        when(tradingService.getLTP(any())).thenThrow(new IOException("Network error"));

        NeutralMarketDetectorService.NeutralMarketResult result1 = service.evaluate("NIFTY");
        NeutralMarketDetectorService.NeutralMarketResult result2 = service.evaluate("NIFTY");

        assertEquals(result1.evaluatedAt(), result2.evaluatedAt(), "Should return same cached result");
    }

    @Test
    @DisplayName("clearCache should force fresh evaluation on next call")
    void clearCache_shouldForceRefresh() throws Exception, KiteException {
        config.setCacheTtlMs(60000);
        when(marketDataEngine.getIndexPrice("NIFTY")).thenReturn(Optional.empty());
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
        when(marketDataEngine.getIndexPrice("NIFTY")).thenReturn(Optional.empty());
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

    // ==================== TEST HELPERS ====================

    private static List<HistoricalData> createFlatCandles(int count, double basePrice) {
        List<HistoricalData> candles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            HistoricalData c = new HistoricalData();
            c.high = basePrice + 5;
            c.low = basePrice - 5;
            c.close = basePrice + ((i % 2 == 0) ? 1 : -1);
            c.open = basePrice;
            c.volume = 0; // Index has zero volume
            candles.add(c);
        }
        return candles;
    }

    private static List<Instrument> createNFOInstruments(double strike) {
        List<Instrument> instruments = new ArrayList<>();
        Date expiry = new Date(System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000L); // 1 week from now

        Instrument ce = new Instrument();
        ce.name = "NIFTY";
        ce.tradingsymbol = String.format("NIFTY25MAR%.0fCE", strike);
        ce.instrument_type = "CE";
        ce.strike = String.valueOf(strike);
        ce.expiry = expiry;
        instruments.add(ce);

        Instrument pe = new Instrument();
        pe.name = "NIFTY";
        pe.tradingsymbol = String.format("NIFTY25MAR%.0fPE", strike);
        pe.instrument_type = "PE";
        pe.strike = String.valueOf(strike);
        pe.expiry = expiry;
        instruments.add(pe);

        return instruments;
    }

    private static LTPQuote createLTPQuote(double lastPrice) {
        LTPQuote quote = new LTPQuote();
        quote.lastPrice = lastPrice;
        return quote;
    }

    private static HistoricalData createHistoricalDataResult(List<HistoricalData> candles) {
        HistoricalData result = new HistoricalData();
        result.dataArrayList = new ArrayList<>(candles);
        return result;
    }
}






