package com.tradingbot.service.strategy;

import com.tradingbot.config.NeutralMarketConfig;
import com.tradingbot.model.NeutralMarketResult;
import com.tradingbot.model.SignalResult;
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
import java.time.LocalTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for NeutralMarketDetectorServiceV2.
 * Tests weighted scoring, regime classification, new signals (oscillation, pullback),
 * time-based adaptation, and lot multiplier recommendations.
 */
@ExtendWith(MockitoExtension.class)
class NeutralMarketDetectorServiceV2Test {

    @Mock
    private MarketDataEngine marketDataEngine;

    @Mock
    private TradingService tradingService;

    @Mock
    private InstrumentCacheService instrumentCacheService;

    private NeutralMarketConfig config;
    private NeutralMarketDetectorServiceV2 service;

    @BeforeEach
    void setUp() throws KiteException, IOException {
        config = new NeutralMarketConfig();
        config.setEnabled(true);
        config.setMinimumScore(6);
        config.setVwapDeviationThreshold(0.002);
        config.setVwapCandleCount(15);
        config.setAdxThreshold(20.0);
        config.setAdxPeriod(7);
        config.setAdxCandleInterval("minute");
        config.setAdxCandleCount(30);
        config.setAdxFallingCheckEnabled(false);
        config.setGammaPinThreshold(0.002);
        config.setStrikesAroundAtm(5);
        config.setRangeCompressionThreshold(0.0035);
        config.setRangeCompressionCandles(5);
        config.setExpiryDayRangeThreshold(0.002);
        config.setCacheTtlMs(30000);
        config.setAllowOnDataUnavailable(true);

        // V2 config
        config.setOscillationCandleCount(10);
        config.setOscillationMinReversals(4);
        config.setVwapPullbackThreshold(0.003);
        config.setVwapPullbackReversionThreshold(0.001);
        config.setVwapPullbackCandleCount(8);
        config.setTimeBasedAdaptationEnabled(false); // Disable for deterministic tests
        config.setWeightVwap(3);
        config.setWeightRange(2);
        config.setWeightOscillation(2);
        config.setWeightVwapPullback(2);
        config.setWeightAdx(1);
        config.setWeightGammaPin(1);
        config.setStrongNeutralThreshold(6);
        config.setWeakNeutralThreshold(4);

        // Mock NSE instruments for instrument token resolution
        List<Instrument> nseInstruments = new ArrayList<>();
        Instrument nifty50 = new Instrument();
        nifty50.tradingsymbol = "NIFTY 50";
        nifty50.instrument_token = 256265L;
        nseInstruments.add(nifty50);
        lenient().when(instrumentCacheService.getInstruments("NSE")).thenReturn(nseInstruments);

        // Override market hours and time-related checks for 24/7 test execution
        service = new NeutralMarketDetectorServiceV2(config, marketDataEngine, tradingService, instrumentCacheService) {
            @Override
            boolean isWithinMarketHours() {
                return true;
            }

            @Override
            boolean hasEnoughTimeForADX() {
                return true;
            }

            @Override
            LocalTime getCurrentISTTime() {
                return LocalTime.of(11, 0); // Mid-session — no time adjustment
            }
        };
    }

    // ==================== DISABLED FILTER ====================

    @Test
    @DisplayName("When filter is disabled, should return tradable with max score")
    void whenDisabled_shouldReturnDisabled() {
        config.setEnabled(false);
        NeutralMarketResult result = service.evaluate("NIFTY");

        assertTrue(result.isTradable());
        assertTrue(result.neutral()); // backward compat
        assertEquals(NeutralMarketResult.MAX_SCORE, result.totalScore());
        assertEquals(1.0, result.getConfidence(), 0.01);
        assertEquals(NeutralMarketResult.REGIME_STRONG_NEUTRAL, result.getRegime());
    }

    // ==================== OUTSIDE MARKET HOURS ====================

    @Test
    @DisplayName("When outside market hours, should return data unavailable (allow by default)")
    void whenOutsideMarketHours_shouldReturnUnavailable() {
        NeutralMarketDetectorServiceV2 closedService =
                new NeutralMarketDetectorServiceV2(config, marketDataEngine, tradingService, instrumentCacheService) {
                    @Override
                    boolean isWithinMarketHours() {
                        return false;
                    }
                };

        NeutralMarketResult result = closedService.evaluate("NIFTY");
        assertTrue(result.isTradable()); // allow-on-data-unavailable = true
        assertEquals(0, result.totalScore());
        assertEquals(NeutralMarketResult.REGIME_TRENDING, result.getRegime());
    }

    @Test
    @DisplayName("When outside market hours with allowOnUnavailable=false, should block")
    void whenOutsideMarketHours_andBlockOnUnavailable_shouldBlock() {
        config.setAllowOnDataUnavailable(false);
        NeutralMarketDetectorServiceV2 closedService =
                new NeutralMarketDetectorServiceV2(config, marketDataEngine, tradingService, instrumentCacheService) {
                    @Override
                    boolean isWithinMarketHours() {
                        return false;
                    }
                };

        NeutralMarketResult result = closedService.evaluate("NIFTY");
        assertFalse(result.isTradable());
    }

    // ==================== FULL EVALUATION — ALL SIGNALS PASS ====================

    @Test
    @DisplayName("When all signals pass, should return STRONG_NEUTRAL with high score")
    void whenAllSignalsPass_shouldReturnStrongNeutral() throws KiteException, IOException {
        setupAllSignalsPass();

        NeutralMarketResult result = service.evaluate("NIFTY");

        assertTrue(result.isTradable());
        assertTrue(result.neutral());
        assertTrue(result.totalScore() >= config.getStrongNeutralThreshold());
        assertEquals(NeutralMarketResult.REGIME_STRONG_NEUTRAL, result.getRegime());
        assertTrue(result.getConfidence() >= 0.6);

        // Check signal breakdown
        assertFalse(result.getSignalBreakdown().isEmpty());
        assertFalse(result.signals().isEmpty());
        assertNotNull(result.summary());
    }

    // ==================== REGIME CLASSIFICATION ====================

    @Test
    @DisplayName("Score >= strong threshold → STRONG_NEUTRAL")
    void scoreAboveStrongThreshold_shouldBeStrongNeutral() throws KiteException, IOException {
        setupAllSignalsPass(); // All signals pass → score ~10
        NeutralMarketResult result = service.evaluate("NIFTY");
        assertEquals(NeutralMarketResult.REGIME_STRONG_NEUTRAL, result.getRegime());
    }

    @Test
    @DisplayName("Score between weak and strong → WEAK_NEUTRAL (still tradable)")
    void scoreBetweenThresholds_shouldBeWeakNeutral() throws KiteException, IOException {
        // Setup so only VWAP (3) + Oscillation (2) pass → score 5
        setupPartialSignals_vwapAndOscillation();
        NeutralMarketResult result = service.evaluate("NIFTY");
        assertTrue(result.isTradable());
        assertEquals(NeutralMarketResult.REGIME_WEAK_NEUTRAL, result.getRegime());
    }

    @Test
    @DisplayName("Score below weak threshold → TRENDING (not tradable)")
    void scoreBelowWeakThreshold_shouldBeTrending() throws KiteException, IOException {
        // Setup minimal — only spot price available, candles too few for signals
        when(marketDataEngine.getIndexPrice("NIFTY")).thenReturn(Optional.of(24000.0));
        when(marketDataEngine.getCandles("NIFTY")).thenReturn(Optional.of(List.of()));
        when(marketDataEngine.getVWAP("NIFTY")).thenReturn(Optional.empty());
        when(instrumentCacheService.getInstruments("NFO")).thenReturn(Collections.emptyList());

        NeutralMarketResult result = service.evaluate("NIFTY");
        assertFalse(result.isTradable());
        assertEquals(NeutralMarketResult.REGIME_TRENDING, result.getRegime());
    }

    // ==================== LOT MULTIPLIER ====================

    @Test
    @DisplayName("Lot multiplier: score 8 → 3x, 6 → 2x, 4 → 1x, 2 → 0x")
    void testLotMultiplier() {
        assertEquals(3, service.getRecommendedLotMultiplier(8));
        assertEquals(3, service.getRecommendedLotMultiplier(10));
        assertEquals(2, service.getRecommendedLotMultiplier(6));
        assertEquals(2, service.getRecommendedLotMultiplier(7));
        assertEquals(1, service.getRecommendedLotMultiplier(4));
        assertEquals(1, service.getRecommendedLotMultiplier(5));
        assertEquals(0, service.getRecommendedLotMultiplier(3));
        assertEquals(0, service.getRecommendedLotMultiplier(0));
    }

    // ==================== TIME-BASED ADAPTATION ====================

    @Test
    @DisplayName("Opening session (9:15-10:00) should subtract 1 from score")
    void openingSession_shouldSubtract1() {
        NeutralMarketDetectorServiceV2 earlyService =
                new NeutralMarketDetectorServiceV2(config, marketDataEngine, tradingService, instrumentCacheService) {
                    @Override boolean isWithinMarketHours() { return true; }
                    @Override boolean hasEnoughTimeForADX() { return true; }
                    @Override LocalTime getCurrentISTTime() { return LocalTime.of(9, 30); }
                };
        assertEquals(-1, earlyService.computeTimeAdjustment());
    }

    @Test
    @DisplayName("Mid-session (10:00-13:30) should have no adjustment")
    void midSession_shouldHaveNoAdjustment() {
        assertEquals(0, service.computeTimeAdjustment()); // Already set to 11:00
    }

    @Test
    @DisplayName("Pre-close session (13:30-15:00) should add 1 to score")
    void preCloseSession_shouldAdd1() {
        NeutralMarketDetectorServiceV2 lateService =
                new NeutralMarketDetectorServiceV2(config, marketDataEngine, tradingService, instrumentCacheService) {
                    @Override boolean isWithinMarketHours() { return true; }
                    @Override boolean hasEnoughTimeForADX() { return true; }
                    @Override LocalTime getCurrentISTTime() { return LocalTime.of(14, 0); }
                };
        assertEquals(1, lateService.computeTimeAdjustment());
    }

    // ==================== PRICE OSCILLATION SIGNAL ====================

    @Test
    @DisplayName("Oscillation: many reversals should pass")
    void oscillation_manyReversals_shouldPass() throws KiteException, IOException {
        when(marketDataEngine.getIndexPrice("NIFTY")).thenReturn(Optional.of(24000.0));
        when(marketDataEngine.getVWAP("NIFTY")).thenReturn(Optional.of(java.math.BigDecimal.valueOf(24000.0)));
        when(instrumentCacheService.getInstruments("NFO")).thenReturn(Collections.emptyList());

        // Create oscillating candles: up, down, up, down, up, down...
        List<HistoricalData> candles = createOscillatingCandles(24000.0, 15);
        when(marketDataEngine.getCandles("NIFTY")).thenReturn(Optional.of(candles));

        NeutralMarketResult result = service.evaluate("NIFTY");
        // Find the oscillation signal
        SignalResult oscSignal = findSignal(result.signals(), "PRICE_OSCILLATION");
        assertNotNull(oscSignal, "PRICE_OSCILLATION signal should be present");
        assertTrue(oscSignal.passed(), "Oscillation signal should pass with many reversals");
        assertEquals(config.getWeightOscillation(), oscSignal.score());
    }

    @Test
    @DisplayName("Oscillation: trending candles should fail")
    void oscillation_trending_shouldFail() throws KiteException, IOException {
        when(marketDataEngine.getIndexPrice("NIFTY")).thenReturn(Optional.of(24000.0));
        when(marketDataEngine.getVWAP("NIFTY")).thenReturn(Optional.of(java.math.BigDecimal.valueOf(24000.0)));
        when(instrumentCacheService.getInstruments("NFO")).thenReturn(Collections.emptyList());

        // Create trending candles: all going up
        List<HistoricalData> candles = createTrendingCandles(24000.0, 15, 5.0);
        when(marketDataEngine.getCandles("NIFTY")).thenReturn(Optional.of(candles));

        NeutralMarketResult result = service.evaluate("NIFTY");
        SignalResult oscSignal = findSignal(result.signals(), "PRICE_OSCILLATION");
        assertNotNull(oscSignal);
        assertFalse(oscSignal.passed(), "Oscillation signal should fail with trending candles");
    }

    // ==================== VWAP PULLBACK SIGNAL ====================

    @Test
    @DisplayName("VWAP Pullback: deviation then reversion should pass")
    void vwapPullback_deviationThenReversion_shouldPass() throws KiteException, IOException {
        double vwap = 24000.0;
        when(marketDataEngine.getIndexPrice("NIFTY")).thenReturn(Optional.of(vwap));
        when(marketDataEngine.getVWAP("NIFTY")).thenReturn(Optional.of(java.math.BigDecimal.valueOf(vwap)));
        when(instrumentCacheService.getInstruments("NFO")).thenReturn(Collections.emptyList());

        // Create pullback pattern: start at VWAP, deviate +0.5%, then revert back
        List<HistoricalData> candles = createPullbackCandles(vwap, 15);
        when(marketDataEngine.getCandles("NIFTY")).thenReturn(Optional.of(candles));

        NeutralMarketResult result = service.evaluate("NIFTY");
        SignalResult pullbackSignal = findSignal(result.signals(), "VWAP_PULLBACK");
        assertNotNull(pullbackSignal, "VWAP_PULLBACK signal should be present");
        assertTrue(pullbackSignal.passed(), "Pullback signal should pass when price deviates and reverts");
    }

    @Test
    @DisplayName("VWAP Pullback: price stays near VWAP should fail")
    void vwapPullback_noDeviation_shouldFail() throws KiteException, IOException {
        double vwap = 24000.0;
        when(marketDataEngine.getIndexPrice("NIFTY")).thenReturn(Optional.of(vwap));
        when(marketDataEngine.getVWAP("NIFTY")).thenReturn(Optional.of(java.math.BigDecimal.valueOf(vwap)));
        when(instrumentCacheService.getInstruments("NFO")).thenReturn(Collections.emptyList());

        // Create candles that stay very close to VWAP (no deviation)
        List<HistoricalData> candles = createFlatCandles(vwap, 15);
        when(marketDataEngine.getCandles("NIFTY")).thenReturn(Optional.of(candles));

        NeutralMarketResult result = service.evaluate("NIFTY");
        SignalResult pullbackSignal = findSignal(result.signals(), "VWAP_PULLBACK");
        assertNotNull(pullbackSignal);
        assertFalse(pullbackSignal.passed(), "Pullback should fail when price stays near VWAP");
    }

    // ==================== VWAP PROXIMITY SIGNAL ====================

    @Test
    @DisplayName("VWAP Proximity: price at VWAP should pass with weight 3")
    void vwapProximity_atVwap_shouldPass() throws KiteException, IOException {
        double price = 24000.0;
        when(marketDataEngine.getIndexPrice("NIFTY")).thenReturn(Optional.of(price));
        when(marketDataEngine.getVWAP("NIFTY")).thenReturn(Optional.of(java.math.BigDecimal.valueOf(price)));
        when(instrumentCacheService.getInstruments("NFO")).thenReturn(Collections.emptyList());

        List<HistoricalData> candles = createFlatCandles(price, 15);
        when(marketDataEngine.getCandles("NIFTY")).thenReturn(Optional.of(candles));

        NeutralMarketResult result = service.evaluate("NIFTY");
        SignalResult vwapSignal = findSignal(result.signals(), "VWAP_PROXIMITY");
        assertNotNull(vwapSignal);
        assertTrue(vwapSignal.passed());
        assertEquals(3, vwapSignal.score()); // Weight = 3
    }

    // ==================== CACHE BEHAVIOR ====================

    @Test
    @DisplayName("Second call within TTL should return cached result")
    void cachedResult_withinTTL_shouldReturnSame() throws KiteException, IOException {
        setupAllSignalsPass();

        NeutralMarketResult result1 = service.evaluate("NIFTY");
        NeutralMarketResult result2 = service.evaluate("NIFTY");

        assertSame(result1, result2, "Second call should return cached instance");
    }

    @Test
    @DisplayName("clearCache should force fresh evaluation")
    void clearCache_shouldForceRefresh() throws KiteException, IOException {
        setupAllSignalsPass();

        NeutralMarketResult result1 = service.evaluate("NIFTY");
        service.clearCache();
        NeutralMarketResult result2 = service.evaluate("NIFTY");

        assertNotSame(result1, result2, "After clear, should return new instance");
    }

    // ==================== BACKWARD COMPATIBILITY ====================

    @Test
    @DisplayName("Backward compat: neutral() alias should match isTradable()")
    void backwardCompat_neutralAlias() throws KiteException, IOException {
        setupAllSignalsPass();
        NeutralMarketResult result = service.evaluate("NIFTY");
        assertEquals(result.isTradable(), result.neutral());
    }

    @Test
    @DisplayName("Backward compat: totalScore() and maxScore() should work")
    void backwardCompat_scoreAccessors() throws KiteException, IOException {
        setupAllSignalsPass();
        NeutralMarketResult result = service.evaluate("NIFTY");
        assertEquals(result.getScore(), result.totalScore());
        assertEquals(NeutralMarketResult.MAX_SCORE, result.maxScore());
    }

    @Test
    @DisplayName("isMarketNeutral convenience method should match evaluate().neutral()")
    void isMarketNeutral_shouldMatchEvaluate() throws KiteException, IOException {
        setupAllSignalsPass();
        boolean neutral = service.isMarketNeutral("NIFTY");
        NeutralMarketResult result = service.evaluate("NIFTY");
        assertEquals(result.neutral(), neutral);
    }

    @Test
    @DisplayName("calculateNeutralScore convenience method should match evaluate().totalScore()")
    void calculateNeutralScore_shouldMatchEvaluate() throws KiteException, IOException {
        setupAllSignalsPass();
        int score = service.calculateNeutralScore("NIFTY");
        NeutralMarketResult result = service.evaluate("NIFTY");
        assertEquals(result.totalScore(), score);
    }

    // ==================== SIGNAL BREAKDOWN ====================

    @Test
    @DisplayName("Signal breakdown map should contain all evaluated signals")
    void signalBreakdown_shouldContainAllSignals() throws KiteException, IOException {
        setupAllSignalsPass();
        NeutralMarketResult result = service.evaluate("NIFTY");

        Map<String, Boolean> breakdown = result.getSignalBreakdown();
        assertNotNull(breakdown);
        assertTrue(breakdown.containsKey("VWAP_PROXIMITY"));
        assertTrue(breakdown.containsKey("RANGE_COMPRESSION"));
        assertTrue(breakdown.containsKey("PRICE_OSCILLATION"));
        assertTrue(breakdown.containsKey("VWAP_PULLBACK"));
        assertTrue(breakdown.containsKey("ADX_TREND"));
        // Gamma pin only on expiry day — not present in this test
    }

    // ==================== GAMMA PIN EXPIRY DAY ====================

    @Test
    @DisplayName("Gamma Pin should not appear on non-expiry days")
    void gammaPin_nonExpiryDay_shouldNotBePresent() throws KiteException, IOException {
        setupAllSignalsPass();
        NeutralMarketResult result = service.evaluate("NIFTY");

        SignalResult gammaSignal = findSignal(result.signals(), "GAMMA_PIN");
        assertNull(gammaSignal, "Gamma pin should not appear on non-expiry days");
        assertFalse(result.getSignalBreakdown().containsKey("GAMMA_PIN"));
    }

    // ==================== HELPER METHODS ====================

    private void setupAllSignalsPass() throws KiteException, IOException {
        double spotPrice = 24000.0;
        double vwap = 24000.0;

        when(marketDataEngine.getIndexPrice("NIFTY")).thenReturn(Optional.of(spotPrice));
        when(marketDataEngine.getVWAP("NIFTY")).thenReturn(Optional.of(java.math.BigDecimal.valueOf(vwap)));
        when(instrumentCacheService.getInstruments("NFO")).thenReturn(Collections.emptyList());

        // Build 30 candles that satisfy ALL signals within their respective windows:
        //   Oscillation window: last 10 candles (idx 20-29) → need ≥4 reversals
        //   Pullback window:    last 8 candles  (idx 22-29) → need deviation ≥0.3% then reversion ≤0.1%
        //   Range compression:  last 5 candles  (idx 25-29) → need range ≤ 0.35%
        //   ADX: all 30 candles with low true range → low ADX
        //   VWAP: spot=24000, vwap=24000 → 0% deviation → PASS
        // Expected total: 3 + 2 + 2 + 2 + 1 = 10 → STRONG_NEUTRAL
        List<HistoricalData> candles = new ArrayList<>(30);

        // Candles 0-19: range-bound candles near VWAP (for ADX calculation)
        for (int i = 0; i < 20; i++) {
            HistoricalData c = new HistoricalData();
            double noise = (i % 2 == 0) ? 2.0 : -2.0;
            c.open = spotPrice + noise;
            c.close = spotPrice - noise;
            c.high = spotPrice + 3;
            c.low = spotPrice - 3;
            c.volume = 1000;
            candles.add(c);
        }

        // Candle 20: up (24005)
        addCandle(candles, spotPrice, spotPrice + 5, spotPrice + 6, spotPrice - 1);
        // Candle 21: down (23995)
        addCandle(candles, spotPrice, spotPrice - 5, spotPrice + 1, spotPrice - 6);
        // Candle 22: up (24005) — oscillation reversal #1
        addCandle(candles, spotPrice, spotPrice + 5, spotPrice + 6, spotPrice - 1);
        // Candle 23: deviation from VWAP → 24096 (0.4% above VWAP) — triggers pullback deviation
        addCandle(candles, spotPrice + 80, spotPrice + 96, spotPrice + 98, spotPrice + 78);
        // Candle 24: still deviated (24090) — reversal #2 (from up at 23, now slightly down)
        addCandle(candles, spotPrice + 92, spotPrice + 90, spotPrice + 94, spotPrice + 88);
        // Candle 25: revert towards VWAP (24001) — reversion found (0.004% < 0.1%) — also within range window
        addCandle(candles, spotPrice + 5, spotPrice + 1, spotPrice + 6, spotPrice - 1);
        // Candle 26: oscillate down (23996) — reversal #3
        addCandle(candles, spotPrice, spotPrice - 4, spotPrice + 2, spotPrice - 5);
        // Candle 27: oscillate up (24004) — reversal #4
        addCandle(candles, spotPrice - 2, spotPrice + 4, spotPrice + 5, spotPrice - 3);
        // Candle 28: oscillate down (23997) — reversal #5
        addCandle(candles, spotPrice + 2, spotPrice - 3, spotPrice + 3, spotPrice - 4);
        // Candle 29: oscillate up (24003) — reversal #6
        addCandle(candles, spotPrice - 1, spotPrice + 3, spotPrice + 4, spotPrice - 2);

        when(marketDataEngine.getCandles("NIFTY")).thenReturn(Optional.of(candles));
    }

    private void addCandle(List<HistoricalData> candles, double open, double close, double high, double low) {
        HistoricalData c = new HistoricalData();
        c.open = open;
        c.close = close;
        c.high = high;
        c.low = low;
        c.volume = 1000;
        candles.add(c);
    }

    private void setupPartialSignals_vwapAndOscillation() throws KiteException, IOException {
        double spotPrice = 24000.0;
        double vwap = 24000.0; // VWAP very close → passes (weight 3)

        when(marketDataEngine.getIndexPrice("NIFTY")).thenReturn(Optional.of(spotPrice));
        when(marketDataEngine.getVWAP("NIFTY")).thenReturn(Optional.of(java.math.BigDecimal.valueOf(vwap)));
        when(instrumentCacheService.getInstruments("NFO")).thenReturn(Collections.emptyList());

        // Build exactly 12 candles where:
        //  - VWAP: PASS (weight 3) — from MDE mock
        //  - OSCILLATION: PASS (weight 2) — alternating close direction in last 10
        //  - RANGE: FAIL — wide highs/lows (>0.35%)
        //  - PULLBACK: FAIL — close oscillates ±5 (0.02%), below pullback threshold (0.3%)
        //  - ADX: UNAVAILABLE — only 12 candles, needs 15 (2*adxPeriod+1 with adxPeriod=7)
        // Total expected score: 3 + 2 = 5 → WEAK_NEUTRAL
        List<HistoricalData> candles = new ArrayList<>(12);
        for (int i = 0; i < 12; i++) {
            HistoricalData c = new HistoricalData();
            // Close oscillates ±5 around VWAP (stays within 0.3% pullback threshold → no pullback deviation triggered)
            double oscillation = (i % 2 == 0) ? 5.0 : -5.0;
            c.open = spotPrice;
            c.close = spotPrice + oscillation;
            // Wide high/low to ensure range compression fails (range = 200 pts → 200/24000 = 0.83% > 0.35%)
            c.high = spotPrice + 100;
            c.low = spotPrice - 100;
            c.volume = 1000;
            candles.add(c);
        }
        when(marketDataEngine.getCandles("NIFTY")).thenReturn(Optional.of(candles));
    }

    private SignalResult findSignal(List<SignalResult> signals, String name) {
        for (int i = 0; i < signals.size(); i++) {
            if (name.equals(signals.get(i).name())) {
                return signals.get(i);
            }
        }
        return null;
    }

    // ==================== CANDLE GENERATORS ====================

    /** Create oscillating candles: close alternates up/down */
    private List<HistoricalData> createOscillatingCandles(double basePrice, int count) {
        List<HistoricalData> candles = new ArrayList<>(count);
        double price = basePrice;
        for (int i = 0; i < count; i++) {
            HistoricalData c = new HistoricalData();
            double delta = (i % 2 == 0) ? 5.0 : -5.0;
            price = basePrice + delta;
            c.open = basePrice;
            c.high = Math.max(basePrice, price) + 2;
            c.low = Math.min(basePrice, price) - 2;
            c.close = price;
            c.volume = 1000;
            candles.add(c);
        }
        return candles;
    }

    /** Create trending candles: all going in one direction */
    private List<HistoricalData> createTrendingCandles(double startPrice, int count, double stepUp) {
        List<HistoricalData> candles = new ArrayList<>(count);
        double price = startPrice;
        for (int i = 0; i < count; i++) {
            HistoricalData c = new HistoricalData();
            c.open = price;
            price += stepUp;
            c.close = price;
            c.high = price + 2;
            c.low = c.open - 2;
            c.volume = 1000;
            candles.add(c);
        }
        return candles;
    }

    /** Create pullback candles: start at VWAP, deviate +0.5%, then revert back */
    private List<HistoricalData> createPullbackCandles(double vwap, int count) {
        List<HistoricalData> candles = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            HistoricalData c = new HistoricalData();
            double closePrice;
            if (i < count / 3) {
                // Phase 1: near VWAP
                closePrice = vwap + (i * 2);
            } else if (i < 2 * count / 3) {
                // Phase 2: deviate from VWAP (0.5% = 120 points at 24000)
                closePrice = vwap + 120 + ((i - count / 3) * 5);
            } else {
                // Phase 3: revert back to VWAP
                int stepsBack = i - (2 * count / 3);
                closePrice = vwap + 120 - (stepsBack * 30);
                if (closePrice < vwap) closePrice = vwap;
            }
            c.open = closePrice - 3;
            c.close = closePrice;
            c.high = closePrice + 5;
            c.low = closePrice - 5;
            c.volume = 1000;
            candles.add(c);
        }
        return candles;
    }

    /** Create flat candles: all very close to the given price */
    private List<HistoricalData> createFlatCandles(double price, int count) {
        List<HistoricalData> candles = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            HistoricalData c = new HistoricalData();
            c.open = price;
            c.close = price + 0.5;
            c.high = price + 1;
            c.low = price - 1;
            c.volume = 1000;
            candles.add(c);
        }
        return candles;
    }

    /** Create candles that pass all neutral signals: oscillating, tight range, with pullback */
    private List<HistoricalData> createComprehensiveNeutralCandles(double basePrice, int count) {
        List<HistoricalData> candles = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            HistoricalData c = new HistoricalData();
            // Oscillating close: alternates around basePrice within tight range
            double oscillation = (i % 2 == 0) ? 3.0 : -3.0;
            double close = basePrice + oscillation;

            // Inject a pullback pattern in the last 8 candles
            if (i >= count - 8 && i < count - 4) {
                // Deviation phase: push price up by 0.4%
                close = basePrice + basePrice * 0.004;
            } else if (i >= count - 4) {
                // Reversion phase: come back near VWAP
                close = basePrice + 1.0;
            }

            c.open = close - 1;
            c.close = close;
            c.high = close + 3;
            c.low = close - 3;
            c.volume = 1000;
            candles.add(c);
        }
        return candles;
    }

    /** Create low-ADX candles (range-bound, small True Range) */
    private List<HistoricalData> createLowADXCandles(double basePrice, int count) {
        List<HistoricalData> candles = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            HistoricalData c = new HistoricalData();
            double noise = (i % 2 == 0) ? 2.0 : -2.0;
            c.open = basePrice + noise;
            c.close = basePrice - noise;
            c.high = basePrice + 5;
            c.low = basePrice - 5;
            c.volume = 1000;
            candles.add(c);
        }
        return candles;
    }

    /** Create oscillating candles with a wide overall range (range compression fails) */
    private List<HistoricalData> createOscillatingWideRangeCandles(double basePrice, int count) {
        List<HistoricalData> candles = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            HistoricalData c = new HistoricalData();
            double oscillation = (i % 2 == 0) ? 50.0 : -50.0;
            c.open = basePrice;
            c.close = basePrice + oscillation;
            c.high = basePrice + 100; // Very wide range
            c.low = basePrice - 100;
            c.volume = 1000;
            candles.add(c);
        }
        return candles;
    }
}



