package com.tradingbot.service.strategy;

import com.tradingbot.config.NeutralMarketV3Config;
import com.tradingbot.model.BreakoutRisk;
import com.tradingbot.model.NeutralMarketEvaluation;
import com.tradingbot.model.NeutralMarketResultV3;
import com.tradingbot.model.Regime;
import com.tradingbot.service.InstrumentCacheService;
import com.tradingbot.service.MarketDataEngine;
import com.tradingbot.service.TradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.Quote;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Neutral Market Detection Engine V3 — 3-Layer Tradable Opportunity Detector.
 *
 * <p>A production-grade, HFT-safe market detection engine that combines three analysis layers
 * to determine not just <em>whether</em> the market is neutral, but whether a <em>real-time
 * tradable opportunity</em> exists right now for SELL ATM STRADDLE on NIFTY 50.</p>
 *
 * <h2>Architecture: 3-Layer Detection</h2>
 * <ol>
 *   <li><b>Regime Layer</b> (0–9 pts): Macro neutrality — VWAP proximity, range compression,
 *       price oscillation, ADX, gamma pin. Classifies market as STRONG_NEUTRAL / WEAK_NEUTRAL / TRENDING.</li>
 *   <li><b>Microstructure Layer</b> (0–5 pts): Immediate tradable signal — VWAP pullback momentum,
 *       high-frequency oscillation, micro range stability. Detects the precise moment to enter.</li>
 *   <li><b>Breakout Risk Layer</b>: Safety gate — tight range + edge proximity + momentum buildup.
 *       Blocks entry when breakout is imminent (HIGH risk).</li>
 * </ol>
 *
 * <h2>Final Decision Logic — Danger Veto + Confidence Scoring</h2>
 * <pre>
 * HARD VETO GATES (any one blocks):
 *   if (breakoutRisk == HIGH)              → NOT tradable
 *   if (excessiveRange detected)           → NOT tradable
 *
 * REGIME-ONLY TRADABILITY:
 *   if (regimeScore >= regimeOnlyMinThreshold) → tradable (micro is bonus for lot sizing)
 *   else                                       → NOT tradable
 * </pre>
 *
 * <h2>HFT Constraints (ALL enforced)</h2>
 * <ul>
 *   <li>NO object creation on the hot read path — cached result returned as-is</li>
 *   <li>All price arithmetic uses {@code double} primitives — zero BigDecimal on hot path</li>
 *   <li>Indexed {@code for} loops only — no Iterator, no Stream, no lambda</li>
 *   <li>Pre-allocated result singleton for disabled state</li>
 *   <li>{@link ConcurrentHashMap} for per-instrument cache — no synchronized blocks</li>
 *   <li>Evaluation runs on a 10–20s cache TTL cycle, not per-tick</li>
 *   <li>Market data read exclusively from {@link MarketDataEngine} cache (zero inline API calls
 *       except gamma pin OI which requires Quote API on expiry days only)</li>
 * </ul>
 *
 * @since 6.0
 * @see NeutralMarketV3Config
 * @see NeutralMarketResultV3
 * @see Regime
 * @see BreakoutRisk
 */
@Service("neutralMarketDetectorV3")
@Slf4j
public class NeutralMarketDetectorServiceV3 implements NeutralMarketDetector {

    // ==================== CONSTANTS ====================

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    // Time-based adaptation boundaries (IST)
    private static final LocalTime OPENING_SESSION_END = LocalTime.of(9, 40);
    private static final LocalTime CLOSING_SESSION_START = LocalTime.of(15, 15);

    // ADX warmup: need ~20 minutes of 1-min candles after open
    private static final int MIN_MINUTES_AFTER_OPEN_FOR_ADX = 20;

    // IST offset for fast epoch-day comparison (avoids Calendar allocation)
    private static final long IST_OFFSET_MS = 19800000L;
    private static final long MS_PER_DAY = 86400000L;

    // Maximum theoretical scores for confidence computation
    private static final double MAX_FINAL_SCORE = 15.0;

    // ==================== DEPENDENCIES ====================

    private final NeutralMarketV3Config config;
    private final MarketDataEngine marketDataEngine;
    private final TradingService tradingService;
    private final InstrumentCacheService instrumentCacheService;

    // ==================== CACHE ====================

    /** Per-instrument cached composite result with TTL. */
    private final ConcurrentHashMap<String, CachedResult> cachedResults = new ConcurrentHashMap<>(4);

    /** Instrument token cache to avoid repeated NSE instrument list scans. */
    private final ConcurrentHashMap<String, String> instrumentTokenCache = new ConcurrentHashMap<>(4);

    // ==================== INTERNAL CACHE RECORD ====================

    private record CachedResult(NeutralMarketResultV3 result, long fetchTimeMs) {
        boolean isExpired(long ttlMs) {
            return System.currentTimeMillis() - fetchTimeMs > ttlMs;
        }
    }

    // ==================== CONSTRUCTOR ====================

    public NeutralMarketDetectorServiceV3(NeutralMarketV3Config config,
                                          MarketDataEngine marketDataEngine,
                                          TradingService tradingService,
                                          InstrumentCacheService instrumentCacheService) {
        this.config = config;
        this.marketDataEngine = marketDataEngine;
        this.tradingService = tradingService;
        this.instrumentCacheService = instrumentCacheService;
    }

    // ==================== PUBLIC API ====================

    /**
     * Evaluate all 3 layers for the given instrument and return a composite decision.
     *
     * <p>Hot-path callers: this method returns a cached result if the TTL has not expired.
     * The actual evaluation (which involves MDE cache reads + ADX computation) runs only
     * once per TTL cycle (default 15s).</p>
     *
     * @param instrumentType "NIFTY" or "BANKNIFTY"
     * @return composite result with tradable decision, scores, regime, and breakout risk
     */
    @Override
    public NeutralMarketResultV3 evaluate(String instrumentType) {
        if (!config.isEnabled()) {
            log.debug("NeutralMarketDetectorV3 disabled, allowing trade");
            return NeutralMarketResultV3.disabled();
        }

        // Check per-instrument cache — zero allocation on cache hit
        String cacheKey = instrumentType.toUpperCase();
        CachedResult cached = cachedResults.get(cacheKey);
        if (cached != null && !cached.isExpired(config.getCacheTtlMs())) {
            log.debug("V3 cache hit: instrument={}, tradable={}, regime={}, finalScore={}",
                    cacheKey, cached.result.isTradable(), cached.result.getRegime(), cached.result.getFinalScore());
            return cached.result;
        }

        // Evaluate fresh
        try {
            NeutralMarketResultV3 result = evaluateAllLayers(instrumentType);
            cachedResults.put(cacheKey, new CachedResult(result, System.currentTimeMillis()));
            return result;
        } catch (Exception e) {
            log.error("V3 evaluation failed for {}: {}", instrumentType, e.getMessage(), e);
            boolean allow = config.isAllowOnDataUnavailable();
            NeutralMarketResultV3 fallback = NeutralMarketResultV3.dataUnavailable(allow, e.getMessage());
            // Cache error result with shorter effective TTL (5s) for faster recovery
            long errorOffset = Math.max(0, config.getCacheTtlMs() - 5000L);
            cachedResults.put(cacheKey, new CachedResult(fallback, System.currentTimeMillis() - errorOffset));
            return fallback;
        }
    }

    /** Convenience: check if a straddle trade should be placed now. */
    public boolean isMarketTradable(String instrumentType) {
        return evaluate(instrumentType).isTradable();
    }

    /** {@inheritDoc} Delegates to {@link #isMarketTradable(String)}. */
    @Override
    public boolean isMarketNeutral(String instrumentType) {
        return isMarketTradable(instrumentType);
    }

    /** Clear all cached state. Useful for testing or forced refresh. */
    @Override
    public void clearCache() {
        cachedResults.clear();
        instrumentTokenCache.clear();
        log.debug("NeutralMarketDetectorV3 cache cleared");
    }

    // ==================================================================================
    //                          CORE EVALUATION — ALL 3 LAYERS
    // ==================================================================================

    private NeutralMarketResultV3 evaluateAllLayers(String instrumentType) {
        long startTime = System.currentTimeMillis();
        log.info("V3 evaluating all layers for {}", instrumentType);

        // ==================== MARKET HOURS GUARD ====================
        if (!isWithinMarketHours()) {
            ZonedDateTime now = ZonedDateTime.now(IST);
            log.warn("V3 evaluation skipped: outside market hours. time={}, day={}", now.toLocalTime(), now.getDayOfWeek());
            return NeutralMarketResultV3.dataUnavailable(config.isAllowOnDataUnavailable(),
                    "Outside market hours (" + now.toLocalTime() + " IST)");
        }

        // ==================== FETCH SHARED DATA (from MDE cache) ====================
        double spotPrice = fetchSpotPrice(instrumentType);
        if (spotPrice <= 0) {
            log.warn("V3: Spot price unavailable for {}", instrumentType);
            return NeutralMarketResultV3.dataUnavailable(config.isAllowOnDataUnavailable(), "Spot price unavailable");
        }

        // Fetch 1-min candles once — shared by regime (VWAP, range, oscillation) + micro + breakout
        List<HistoricalData> candles = fetchOneMinuteCandles(instrumentType);
        if (candles == null || candles.size() < 5) {
            log.warn("V3: Insufficient candle data for {}: {}", instrumentType, candles == null ? 0 : candles.size());
            return NeutralMarketResultV3.dataUnavailable(config.isAllowOnDataUnavailable(),
                    "Insufficient candle data: " + (candles == null ? 0 : candles.size()));
        }

        // Compute VWAP once — shared by regime VWAP proximity + micro VWAP pullback
        double vwap = computeVWAP(candles, instrumentType);

        // Determine if expiry day (for gamma pin)
        long todayEpochDay = (startTime + IST_OFFSET_MS) / MS_PER_DAY;
        boolean isExpiryDay = false;
        List<Instrument> nfoInstruments = Collections.emptyList();
        Date nearestExpiry = null;
        try {
            Optional<Date> cachedExpiry = marketDataEngine.getNearestWeeklyExpiry(instrumentType);
            if (cachedExpiry.isPresent()) {
                nearestExpiry = cachedExpiry.get();
                isExpiryDay = ((nearestExpiry.getTime() + IST_OFFSET_MS) / MS_PER_DAY) == todayEpochDay;
            }
        } catch (Exception e) {
            log.debug("V3: MDE expiry cache miss for {}: {}", instrumentType, e.getMessage());
        }

        // Load NFO instruments if expiry day (needed for gamma pin OI)
        if (isExpiryDay) {
            try {
                nfoInstruments = instrumentCacheService.getInstruments("NFO");
            } catch (KiteException | IOException e) {
                log.warn("V3: Failed to fetch NFO instruments for gamma pin: {}", e.getMessage());
            }
        }

        double strikeInterval = getStrikeInterval(instrumentType);

        // ==================== SIGNAL BREAKDOWN MAP ====================
        // Pre-allocate with expected capacity: 5 regime + 3 micro + 3 breakout = 11
        Map<String, Boolean> signalMap = new LinkedHashMap<>(12);

        // ==================== PER-SIGNAL NUMERIC TRACKERS (for persistence) ====================
        double numericVwapDeviation = (vwap > 0) ? Math.abs(spotPrice - vwap) / vwap : 0.0;
        double numericRangeFraction = 0.0;
        int numericOscillationReversals = 0;
        double numericAdxValue = 0.0;

        // ======================================================================
        //                       LAYER 1: REGIME (0–9 pts)
        // ======================================================================
        int regimeScore = 0;

        // Signal R1: VWAP Proximity (+3)
        boolean vwapProximityPassed = evaluateVwapProximity(spotPrice, vwap);
        signalMap.put("VWAP_PROXIMITY", vwapProximityPassed);
        if (vwapProximityPassed) {
            regimeScore += config.getWeightVwapProximity();
        }

        // Signal R2: Range Compression (+2)
        boolean rangeCompressionPassed = evaluateRangeCompression(spotPrice, candles);
        signalMap.put("RANGE_COMPRESSION", rangeCompressionPassed);
        if (rangeCompressionPassed) {
            regimeScore += config.getWeightRangeCompression();
        }
        // Capture numeric range fraction for persistence
        numericRangeFraction = computeRangeFraction(spotPrice, candles, config.getRangeCompressionCandles());

        // Signal R3: Price Oscillation (+2)
        boolean oscillationPassed = evaluateOscillation(candles);
        signalMap.put("OSCILLATION", oscillationPassed);
        if (oscillationPassed) {
            regimeScore += config.getWeightOscillation();
        }
        // Capture numeric oscillation reversals for persistence
        numericOscillationReversals = computeReversalCount(candles, config.getOscillationCandleCount());

        // Signal R4: ADX Trend Strength (+1)
        boolean adxPassed = evaluateADX(instrumentType);
        signalMap.put("ADX_TREND", adxPassed);
        if (adxPassed) {
            regimeScore += config.getWeightAdx();
        }
        // Capture numeric ADX value for persistence
        numericAdxValue = computeLatestADX(instrumentType);

        // Signal R5: Gamma Pin (+1) — expiry day only
        boolean gammaPinPassed = false;
        if (isExpiryDay) {
            gammaPinPassed = evaluateGammaPin(spotPrice, instrumentType, strikeInterval, nfoInstruments, nearestExpiry);
            signalMap.put("GAMMA_PIN", gammaPinPassed);
            if (gammaPinPassed) {
                regimeScore += config.getWeightGammaPin();
            }
        }

        // Classify regime
        Regime regime;
        if (regimeScore >= config.getRegimeStrongNeutralThreshold()) {
            regime = Regime.STRONG_NEUTRAL;
        } else if (regimeScore >= config.getRegimeWeakNeutralThreshold()) {
            regime = Regime.WEAK_NEUTRAL;
        } else {
            regime = Regime.TRENDING;
        }

        log.debug("V3 Regime: score={}, regime={}, vwap={}, range={}, osc={}, adx={}, gamma={}",
                regimeScore, regime, vwapProximityPassed, rangeCompressionPassed,
                oscillationPassed, adxPassed, isExpiryDay ? gammaPinPassed : "N/A");

        // ======================================================================
        //                  LAYER 2: MICROSTRUCTURE (0–5 pts)
        // ======================================================================
        int microScore = 0;

        // Signal M1: VWAP Pullback Momentum (+2)
        boolean microVwapPullbackPassed = evaluateMicroVwapPullback(candles, vwap, spotPrice);
        signalMap.put("MICRO_VWAP_PULLBACK", microVwapPullbackPassed);
        if (microVwapPullbackPassed) {
            microScore += config.getWeightMicroVwapPullback();
        }

        // Signal M2: High-Frequency Oscillation (+2)
        boolean microOscillationPassed = evaluateMicroOscillation(candles, spotPrice);
        signalMap.put("MICRO_HF_OSCILLATION", microOscillationPassed);
        if (microOscillationPassed) {
            microScore += config.getWeightMicroOscillation();
        }

        // Signal M3: Micro Range Stability (+1)
        boolean microRangePassed = evaluateMicroRangeStability(candles, spotPrice);
        signalMap.put("MICRO_RANGE_STABILITY", microRangePassed);
        if (microRangePassed) {
            microScore += config.getWeightMicroRangeStability();
        }

        boolean microTradable = microScore >= 2;

        log.debug("V3 Micro: score={}, tradable={}, pullback={}, hfOsc={}, range={}",
                microScore, microTradable, microVwapPullbackPassed, microOscillationPassed, microRangePassed);

        // ======================================================================
        //                   LAYER 3: BREAKOUT RISK
        // ======================================================================
        BreakoutRisk breakoutRisk = evaluateBreakoutRisk(candles, spotPrice);
        signalMap.put("BREAKOUT_RISK_LOW", breakoutRisk == BreakoutRisk.LOW);

        log.debug("V3 Breakout: risk={}", breakoutRisk);

        // ======================================================================
        //                   EXCESSIVE RANGE VETO GATE
        // ======================================================================
        boolean excessiveRange = evaluateExcessiveRange(candles, spotPrice);
        signalMap.put("EXCESSIVE_RANGE_SAFE", !excessiveRange);

        log.debug("V3 ExcessiveRange: vetoed={}", excessiveRange);

        // ======================================================================
        //                   TIME-BASED ADJUSTMENT
        // ======================================================================
        int timeAdjustment = 0;
        if (config.isTimeBasedAdaptationEnabled()) {
            timeAdjustment = computeTimeAdjustment();
        }

        // ======================================================================
        //            FINAL DECISION LOGIC — DANGER VETO + CONFIDENCE SCORING
        // ======================================================================
        int rawFinalScore = regimeScore + microScore;
        int finalScore = Math.max(0, Math.min(rawFinalScore + timeAdjustment, 15));

        // New decision model:
        // 1. HARD VETO: Breakout risk HIGH → not tradable (safety gate)
        // 2. HARD VETO: Excessive range → not tradable (strong directional move)
        // 3. REGIME-ONLY GATE: regimeScore >= regimeOnlyMinimumThreshold → tradable
        //    Micro signals are BONUS for lot sizing, not a hard requirement.
        // 4. Else → not tradable
        boolean tradable;
        String vetoReason = null;
        if (breakoutRisk == BreakoutRisk.HIGH) {
            tradable = false;
            vetoReason = "BREAKOUT_HIGH";
        } else if (excessiveRange) {
            tradable = false;
            vetoReason = "EXCESSIVE_RANGE";
        } else if (regimeScore >= config.getRegimeOnlyMinimumThreshold()) {
            tradable = true;
        } else {
            tradable = false;
        }

        // ======================================================================
        //                   CONFIDENCE
        // ======================================================================
        double confidence = finalScore / MAX_FINAL_SCORE;
        if (confidence > 1.0) confidence = 1.0;
        if (confidence < 0.0) confidence = 0.0;

        // ======================================================================
        //                   SUMMARY & LOGGING
        // ======================================================================
        StringBuilder sb = new StringBuilder(256);
        sb.append("R[");
        sb.append(vwapProximityPassed ? "V✓" : "V✗");
        sb.append(rangeCompressionPassed ? " RC✓" : " RC✗");
        sb.append(oscillationPassed ? " O✓" : " O✗");
        sb.append(adxPassed ? " A✓" : " A✗");
        if (isExpiryDay) {
            sb.append(gammaPinPassed ? " G✓" : " G✗");
        }
        sb.append("]=").append(regimeScore);
        sb.append(" M[");
        sb.append(microVwapPullbackPassed ? "VP✓" : "VP✗");
        sb.append(microOscillationPassed ? " HF✓" : " HF✗");
        sb.append(microRangePassed ? " RS✓" : " RS✗");
        sb.append("]=").append(microScore);
        sb.append(" BR=").append(breakoutRisk);
        if (excessiveRange) {
            sb.append(" ER=VETO");
        }
        if (timeAdjustment != 0) {
            sb.append(" T=").append(timeAdjustment > 0 ? "+" : "").append(timeAdjustment);
        }
        if (vetoReason != null) {
            sb.append(" VETO=").append(vetoReason);
        }
        sb.append(" → ").append(tradable ? "TRADE" : "SKIP");
        String summary = sb.toString();

        long elapsed = System.currentTimeMillis() - startTime;

        log.info("V3 decision: instrument={}, price={}, regime={}, regimeScore={}, microScore={}, " +
                        "finalScore={}, breakoutRisk={}, excessiveRange={}, tradable={}, confidence={}, " +
                        "vetoReason={}, elapsedMs={}, signals=[{}]",
                instrumentType, String.format("%.2f", spotPrice), regime, regimeScore, microScore,
                finalScore, breakoutRisk, excessiveRange, tradable,
                String.format("%.2f", confidence), vetoReason, elapsed, summary);

        return new NeutralMarketResultV3(
                tradable, regimeScore, microScore, finalScore, confidence,
                regime, breakoutRisk, microTradable,
                signalMap, summary, Instant.ofEpochMilli(startTime),
                spotPrice, vwap, elapsed, vetoReason, timeAdjustment, isExpiryDay,
                numericVwapDeviation, numericRangeFraction,
                numericOscillationReversals, numericAdxValue
        );
    }

    // ==================================================================================
    //                       REGIME LAYER SIGNAL EVALUATORS
    // ==================================================================================

    /**
     * R1: VWAP Proximity — abs(price - vwap) / vwap < threshold.
     * Highest weight (3): VWAP proximity is the strongest predictor of neutral markets.
     *
     * @return true if spot price is within threshold of VWAP
     */
    private boolean evaluateVwapProximity(double spotPrice, double vwap) {
        if (vwap <= 0) {
            log.debug("V3 R1 VWAP_PROXIMITY: VWAP unavailable, failing signal");
            return false;
        }
        double deviation = Math.abs(spotPrice - vwap) / vwap;
        boolean passed = deviation < config.getVwapProximityThreshold();
        log.debug("V3 R1 VWAP_PROXIMITY: price={}, vwap={}, deviation={}, threshold={}, passed={}",
                String.format("%.2f", spotPrice), String.format("%.2f", vwap),
                String.format("%.5f", deviation), config.getVwapProximityThreshold(), passed);
        return passed;
    }

    /**
     * R2: Range Compression — (highestHigh - lowestLow) / price over last N candles.
     * Tight range = low volatility = safe for straddle selling.
     *
     * @return true if range is below threshold
     */
    private boolean evaluateRangeCompression(double spotPrice, List<HistoricalData> candles) {
        int required = config.getRangeCompressionCandles();
        if (candles.size() < required) {
            log.debug("V3 R2 RANGE_COMPRESSION: insufficient candles ({}/{})", candles.size(), required);
            return false;
        }

        int startIdx = candles.size() - required;
        double highestHigh = Double.MIN_VALUE;
        double lowestLow = Double.MAX_VALUE;

        for (int i = startIdx; i < candles.size(); i++) {
            HistoricalData c = candles.get(i);
            if (c.high > highestHigh) highestHigh = c.high;
            if (c.low < lowestLow) lowestLow = c.low;
        }

        double rangeFraction = (highestHigh - lowestLow) / spotPrice;
        boolean passed = rangeFraction < config.getRangeCompressionThreshold();
        log.debug("V3 R2 RANGE_COMPRESSION: range={}, rangePct={}, threshold={}, candles={}, passed={}",
                String.format("%.2f", highestHigh - lowestLow), String.format("%.5f", rangeFraction),
                config.getRangeCompressionThreshold(), required, passed);
        return passed;
    }

    /**
     * R3: Price Oscillation — count direction reversals (close-to-close) in last N candles.
     * Many reversals = choppy market = neutral = good for straddle selling.
     *
     * <p>HFT: Indexed for loop, primitive int comparisons, zero allocations.</p>
     *
     * @return true if reversals >= minimum threshold
     */
    private boolean evaluateOscillation(List<HistoricalData> candles) {
        int required = config.getOscillationCandleCount();
        if (candles.size() < required) {
            log.debug("V3 R3 OSCILLATION: insufficient candles ({}/{})", candles.size(), required);
            return false;
        }

        int startIdx = candles.size() - required;
        int reversals = 0;
        int previousDirection = 0; // +1=up, -1=down, 0=flat

        for (int i = startIdx + 1; i < candles.size(); i++) {
            double prevClose = candles.get(i - 1).close;
            double currClose = candles.get(i).close;

            int direction;
            if (currClose > prevClose) {
                direction = 1;
            } else if (currClose < prevClose) {
                direction = -1;
            } else {
                direction = 0; // Flat — no reversal counted
            }

            // Reversal: direction changed from positive↔negative
            if (direction != 0 && previousDirection != 0 && direction != previousDirection) {
                reversals++;
            }

            if (direction != 0) {
                previousDirection = direction;
            }
        }

        boolean passed = reversals >= config.getOscillationMinReversals();
        log.debug("V3 R3 OSCILLATION: reversals={}, minRequired={}, candles={}, passed={}",
                reversals, config.getOscillationMinReversals(), required, passed);
        return passed;
    }

    // ==================================================================================
    //            NUMERIC VALUE EXTRACTORS (for persistence enrichment)
    // ==================================================================================

    /**
     * Compute range fraction = (highestHigh − lowestLow) / spotPrice for persistence.
     * Lightweight double arithmetic; duplicates R2 logic but returns the numeric value.
     */
    private double computeRangeFraction(double spotPrice, List<HistoricalData> candles, int required) {
        if (spotPrice <= 0 || candles == null || candles.size() < required) return 0.0;
        int startIdx = candles.size() - required;
        double highestHigh = Double.MIN_VALUE;
        double lowestLow = Double.MAX_VALUE;
        for (int i = startIdx; i < candles.size(); i++) {
            HistoricalData c = candles.get(i);
            if (c.high > highestHigh) highestHigh = c.high;
            if (c.low < lowestLow) lowestLow = c.low;
        }
        return (highestHigh - lowestLow) / spotPrice;
    }

    /**
     * Count direction reversals for persistence. Duplicates R3 logic but returns int count.
     */
    private int computeReversalCount(List<HistoricalData> candles, int required) {
        if (candles == null || candles.size() < required) return 0;
        int startIdx = candles.size() - required;
        int reversals = 0;
        int previousDirection = 0;
        for (int i = startIdx + 1; i < candles.size(); i++) {
            double prevClose = candles.get(i - 1).close;
            double currClose = candles.get(i).close;
            int direction = (currClose > prevClose) ? 1 : (currClose < prevClose) ? -1 : 0;
            if (direction != 0 && previousDirection != 0 && direction != previousDirection) {
                reversals++;
            }
            if (direction != 0) previousDirection = direction;
        }
        return reversals;
    }

    /**
     * Compute latest ADX value for persistence. Returns 0 if unavailable.
     */
    private double computeLatestADX(String instrumentType) {
        try {
            if (!hasEnoughTimeForADX()) return 0.0;
            String instrumentToken = resolveInstrumentToken(instrumentType);
            List<HistoricalData> adxCandles = fetchADXCandles(instrumentType, instrumentToken);
            int minRequired = config.getAdxPeriod() * 2 + 1;
            if (adxCandles == null || adxCandles.size() < minRequired) return 0.0;
            double[] adxValues = NeutralMarketDetectorService.computeADXSeries(adxCandles, config.getAdxPeriod());
            return (adxValues.length > 0) ? adxValues[adxValues.length - 1] : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * R4: ADX Trend Strength — ADX below threshold = no strong trend = ranging market.
     * Low weight (1) because ADX is a lagging indicator; used only as confirmation.
     *
     * <p>Reuses the static {@link NeutralMarketDetectorService#computeADXSeries} for Wilder's
     * smoothed ADX computation. Candles sourced from MarketDataEngine cache.</p>
     *
     * @return true if ADX < threshold
     */
    private boolean evaluateADX(String instrumentType) {
        // Early session guard: not enough candles for meaningful ADX
        if (!hasEnoughTimeForADX()) {
            log.debug("V3 R4 ADX: early session, insufficient time for ADX. Failing signal.");
            return false;
        }

        // Fetch candles for ADX (may use different interval than 1-min)
        String instrumentToken = resolveInstrumentToken(instrumentType);
        List<HistoricalData> adxCandles = fetchADXCandles(instrumentType, instrumentToken);

        int minRequired = config.getAdxPeriod() * 2 + 1;
        if (adxCandles == null || adxCandles.size() < minRequired) {
            log.debug("V3 R4 ADX: insufficient candles ({}/{})", adxCandles == null ? 0 : adxCandles.size(), minRequired);
            return false;
        }

        // Compute ADX using V1's proven static method
        double[] adxValues = NeutralMarketDetectorService.computeADXSeries(adxCandles, config.getAdxPeriod());
        if (adxValues.length == 0) {
            log.debug("V3 R4 ADX: computation returned no values");
            return false;
        }

        double latestADX = adxValues[adxValues.length - 1];
        boolean passed = latestADX < config.getAdxThreshold();
        log.debug("V3 R4 ADX: value={}, threshold={}, passed={}",
                String.format("%.2f", latestADX), config.getAdxThreshold(), passed);
        return passed;
    }

    /**
     * R5: Gamma Pin — spot price pinned near max-OI strike (expiry day only).
     * Uses batch Quote API to fetch open interest for strikes around ATM.
     *
     * <p>Note: This is the only signal that requires a live API call (Quote for OI).
     * On non-expiry days, this signal is skipped entirely (zero API cost).</p>
     *
     * @return true if spot is within threshold of max-OI strike
     */
    private boolean evaluateGammaPin(double spotPrice, String instrumentType, double strikeInterval,
                                      List<Instrument> nfoInstruments, Date nearestExpiry) {
        if (nfoInstruments == null || nfoInstruments.isEmpty() || nearestExpiry == null) {
            log.debug("V3 R5 GAMMA_PIN: NFO instruments or expiry unavailable");
            return false;
        }

        try {
            double atmStrike = Math.round(spotPrice / strikeInterval) * strikeInterval;
            int strikesAround = config.getStrikesAroundAtm();
            String underlyingName = getUnderlyingName(instrumentType);

            // Build target strikes array around ATM
            int totalStrikes = 2 * strikesAround + 1;
            double[] targetStrikes = new double[totalStrikes];
            for (int i = -strikesAround; i <= strikesAround; i++) {
                targetStrikes[i + strikesAround] = atmStrike + i * strikeInterval;
            }

            // Map target strike index → [CE symbol, PE symbol]
            String[][] strikeSymbols = new String[totalStrikes][2];

            // Scan NFO instruments to match target strikes + expiry
            for (int instIdx = 0; instIdx < nfoInstruments.size(); instIdx++) {
                Instrument inst = nfoInstruments.get(instIdx);
                if (!underlyingName.equals(inst.name)) continue;
                if (inst.expiry == null || !isSameDay(inst.expiry, nearestExpiry)) continue;

                double strike;
                try {
                    strike = Double.parseDouble(inst.strike);
                } catch (NumberFormatException ignored) {
                    continue;
                }

                for (int j = 0; j < totalStrikes; j++) {
                    if (Math.abs(strike - targetStrikes[j]) < 0.01) {
                        if ("CE".equals(inst.instrument_type)) {
                            strikeSymbols[j][0] = "NFO:" + inst.tradingsymbol;
                        } else if ("PE".equals(inst.instrument_type)) {
                            strikeSymbols[j][1] = "NFO:" + inst.tradingsymbol;
                        }
                        break;
                    }
                }
            }

            // Collect all valid symbols for batch quote fetch
            List<String> allSymbols = new ArrayList<>(totalStrikes * 2);
            for (int j = 0; j < totalStrikes; j++) {
                if (strikeSymbols[j][0] != null) allSymbols.add(strikeSymbols[j][0]);
                if (strikeSymbols[j][1] != null) allSymbols.add(strikeSymbols[j][1]);
            }

            if (allSymbols.isEmpty()) {
                log.debug("V3 R5 GAMMA_PIN: no option symbols found for OI fetch");
                return false;
            }

            // Batch fetch quotes (contains OI field)
            Map<String, Quote> quotes = tradingService.getQuote(allSymbols.toArray(new String[0]));
            if (quotes == null || quotes.isEmpty()) {
                log.debug("V3 R5 GAMMA_PIN: quote fetch returned empty");
                return false;
            }

            // Aggregate OI per strike and find max
            double maxOI = 0;
            double maxOIStrike = atmStrike;
            for (int j = 0; j < totalStrikes; j++) {
                double combinedOI = 0;
                if (strikeSymbols[j][0] != null) {
                    Quote q = quotes.get(strikeSymbols[j][0]);
                    if (q != null) combinedOI += q.oi;
                }
                if (strikeSymbols[j][1] != null) {
                    Quote q = quotes.get(strikeSymbols[j][1]);
                    if (q != null) combinedOI += q.oi;
                }
                if (combinedOI > maxOI) {
                    maxOI = combinedOI;
                    maxOIStrike = targetStrikes[j];
                }
            }

            double distance = Math.abs(spotPrice - maxOIStrike) / spotPrice;
            boolean passed = distance < config.getGammaPinThreshold();
            log.debug("V3 R5 GAMMA_PIN: price={}, maxOIStrike={}, maxOI={}, distance={}, threshold={}, passed={}",
                    String.format("%.2f", spotPrice), String.format("%.0f", maxOIStrike),
                    String.format("%.0f", maxOI), String.format("%.5f", distance),
                    config.getGammaPinThreshold(), passed);
            return passed;

        } catch (Exception | KiteException e) {
            log.warn("V3 R5 GAMMA_PIN evaluation failed: {}", e.getMessage());
            return false;
        }
    }

    // ==================================================================================
    //                    MICROSTRUCTURE LAYER SIGNAL EVALUATORS
    // ==================================================================================

    /**
     * M1: VWAP Pullback Momentum — detect price moving away from VWAP then reversing toward it.
     *
     * <p>Algorithm (single-pass, zero allocation):</p>
     * <ol>
     *   <li>In last N candles, find the point of maximum deviation from VWAP</li>
     *   <li>After that peak deviation, check if the last 3 candle closes show
     *       progressively decreasing deviation (slope reverting toward VWAP)</li>
     * </ol>
     *
     * <p>This detects the precise moment when mean-reversion starts — optimal entry
     * for a straddle because the price is pulling back to the center.</p>
     *
     * @return true if pullback reversal toward VWAP is detected
     */
    private boolean evaluateMicroVwapPullback(List<HistoricalData> candles, double vwap, double spotPrice) {
        if (vwap <= 0) {
            log.debug("V3 M1 MICRO_VWAP_PULLBACK: VWAP unavailable");
            return false;
        }

        int windowSize = config.getMicroVwapPullbackCandles();
        if (candles.size() < windowSize) {
            log.debug("V3 M1 MICRO_VWAP_PULLBACK: insufficient candles ({}/{})", candles.size(), windowSize);
            return false;
        }

        int startIdx = candles.size() - windowSize;
        double deviationThreshold = config.getMicroVwapPullbackDeviationThreshold();

        // Phase 1: Find the candle with maximum deviation from VWAP
        int maxDeviationIdx = -1;
        double maxDeviation = 0;
        for (int i = startIdx; i < candles.size(); i++) {
            double deviation = Math.abs(candles.get(i).close - vwap) / vwap;
            if (deviation > maxDeviation) {
                maxDeviation = deviation;
                maxDeviationIdx = i;
            }
        }

        // Must have deviated beyond minimum threshold
        if (maxDeviation < deviationThreshold) {
            log.debug("V3 M1 MICRO_VWAP_PULLBACK: maxDeviation={} < threshold={}, no pullback",
                    String.format("%.5f", maxDeviation), deviationThreshold);
            return false;
        }

        // Phase 2: Check if last slopeCandles closes show reverting slope toward VWAP
        // (each subsequent close has smaller deviation from VWAP than the previous)
        int slopeCandles = config.getMicroVwapPullbackSlopeCandles();
        int slopeStart = candles.size() - slopeCandles;

        // Slope check only makes sense if the max deviation was before the slope window
        if (maxDeviationIdx >= slopeStart) {
            // Max deviation is too recent — no reversion yet
            // But allow it if it's at the start of the slope window
            if (maxDeviationIdx > slopeStart) {
                log.debug("V3 M1 MICRO_VWAP_PULLBACK: max deviation at idx {} is within slope window (start={}), no reversion yet",
                        maxDeviationIdx, slopeStart);
                return false;
            }
        }

        // Check mostly-decreasing deviation in the slope window (with small tolerance)
        // Strict monotonic requirement misses valid pullbacks where a single candle pauses
        boolean reverting = true;
        double toleranceFraction = 0.0002; // 0.02% — small bump tolerance
        double firstDev = Math.abs(candles.get(slopeStart).close - vwap) / vwap;
        double prevDev = firstDev;
        for (int i = slopeStart + 1; i < candles.size(); i++) {
            double currDev = Math.abs(candles.get(i).close - vwap) / vwap;
            if (currDev > prevDev + toleranceFraction) {
                reverting = false;
                break;
            }
            prevDev = currDev;
        }
        // Overall slope must be downward (last deviation < first deviation)
        double lastCandleDev = prevDev;
        if (reverting && lastCandleDev >= firstDev) {
            reverting = false;
        }

        // Validate with current spot price — reversion must still hold right now
        if (reverting && spotPrice > 0) {
            double spotDev = Math.abs(spotPrice - vwap) / vwap;
            if (spotDev > lastCandleDev + toleranceFraction) {
                reverting = false; // spot moved away again — reversion invalidated
            }
        }

        log.debug("V3 M1 MICRO_VWAP_PULLBACK: maxDev={}, maxDevIdx={}, reverting={}, slopeCandles={}, passed={}",
                String.format("%.5f", maxDeviation), maxDeviationIdx - startIdx, reverting, slopeCandles, reverting);
        return reverting;
    }

    /**
     * M2: High-Frequency Oscillation — direction flips with small amplitude.
     *
     * <p>Dual condition: high flip count AND small average move per candle.
     * This confirms the price is oscillating rapidly in a tight band — the ideal
     * microstructure for straddle entry because both CE and PE decay symmetrically.</p>
     *
     * <p>HFT: Single pass, primitive arithmetic, zero allocations.</p>
     *
     * @return true if flip_count >= threshold AND avg_move < threshold
     */
    private boolean evaluateMicroOscillation(List<HistoricalData> candles, double spotPrice) {
        int required = config.getMicroOscillationCandles();
        if (candles.size() < required) {
            log.debug("V3 M2 MICRO_HF_OSCILLATION: insufficient candles ({}/{})", candles.size(), required);
            return false;
        }

        int startIdx = candles.size() - required;
        int flipCount = 0;
        double totalAbsMove = 0;
        int previousDirection = 0; // +1=up, -1=down

        for (int i = startIdx + 1; i < candles.size(); i++) {
            double prevClose = candles.get(i - 1).close;
            double currClose = candles.get(i).close;
            double move = currClose - prevClose;
            totalAbsMove += Math.abs(move);

            int direction;
            if (move > 0) {
                direction = 1;
            } else if (move < 0) {
                direction = -1;
            } else {
                direction = 0;
            }

            if (direction != 0 && previousDirection != 0 && direction != previousDirection) {
                flipCount++;
            }
            if (direction != 0) {
                previousDirection = direction;
            }
        }

        int moveCount = required - 1;
        double avgMove = (moveCount > 0 && spotPrice > 0) ? (totalAbsMove / moveCount) / spotPrice : 0;

        boolean flipsPassed = flipCount >= config.getMicroOscillationMinFlips();
        boolean amplitudePassed = avgMove < config.getMicroOscillationMaxAvgMove();
        boolean passed = flipsPassed && amplitudePassed;

        log.debug("V3 M2 MICRO_HF_OSCILLATION: flips={}, minFlips={}, avgMove={}, maxAvgMove={}, passed={}",
                flipCount, config.getMicroOscillationMinFlips(),
                String.format("%.6f", avgMove), config.getMicroOscillationMaxAvgMove(), passed);
        return passed;
    }

    /**
     * M3: Micro Range Stability — last N candles confined to a very tight range.
     *
     * <p>Confirms immediate price stability. Unlike regime-level range compression
     * (which looks at 10 candles), this checks a shorter window (5 candles) with a
     * tighter threshold to detect micro-level calm.</p>
     *
     * @return true if (high-low)/price over last N candles < threshold
     */
    private boolean evaluateMicroRangeStability(List<HistoricalData> candles, double spotPrice) {
        int required = config.getMicroRangeCandles();
        if (candles.size() < required) {
            log.debug("V3 M3 MICRO_RANGE_STABILITY: insufficient candles ({}/{})", candles.size(), required);
            return false;
        }

        int startIdx = candles.size() - required;
        double highestHigh = Double.MIN_VALUE;
        double lowestLow = Double.MAX_VALUE;

        for (int i = startIdx; i < candles.size(); i++) {
            HistoricalData c = candles.get(i);
            if (c.high > highestHigh) highestHigh = c.high;
            if (c.low < lowestLow) lowestLow = c.low;
        }

        double rangeFraction = (highestHigh - lowestLow) / spotPrice;
        boolean passed = rangeFraction < config.getMicroRangeThreshold();

        log.debug("V3 M3 MICRO_RANGE_STABILITY: range={}, rangeFraction={}, threshold={}, candles={}, passed={}",
                String.format("%.2f", highestHigh - lowestLow), String.format("%.6f", rangeFraction),
                config.getMicroRangeThreshold(), required, passed);
        return passed;
    }

    // ==================================================================================
    //                       BREAKOUT RISK LAYER
    // ==================================================================================

    /**
     * Evaluate breakout risk by checking three conditions that together form the
     * classic breakout setup:
     *
     * <ol>
     *   <li><b>Tight Range</b>: Consolidation range is very narrow (spring loading)</li>
     *   <li><b>Edge Proximity</b>: Price is pressing against the high or low of the range</li>
     *   <li><b>Momentum Buildup</b>: Last N candles are all in the same direction</li>
     * </ol>
     *
     * <p>When all 3 align, a breakout is imminent → HIGH risk → block straddle entry.</p>
     *
     * <p>HFT: Two simple passes through candle array, all primitive arithmetic.</p>
     *
     * @return BreakoutRisk enum (LOW, MEDIUM, HIGH)
     */
    private BreakoutRisk evaluateBreakoutRisk(List<HistoricalData> candles, double spotPrice) {
        int rangeCandles = config.getBreakoutRangeCandles();
        if (candles.size() < rangeCandles) {
            log.debug("V3 BREAKOUT: insufficient candles for analysis, defaulting to LOW");
            return BreakoutRisk.LOW;
        }

        int startIdx = candles.size() - rangeCandles;

        // Condition 1: Tight range
        double highestHigh = Double.MIN_VALUE;
        double lowestLow = Double.MAX_VALUE;

        for (int i = startIdx; i < candles.size(); i++) {
            HistoricalData c = candles.get(i);
            if (c.high > highestHigh) highestHigh = c.high;
            if (c.low < lowestLow) lowestLow = c.low;
        }

        double range = highestHigh - lowestLow;
        double rangeFraction = (spotPrice > 0) ? range / spotPrice : 1.0;
        boolean tightRange = rangeFraction < config.getBreakoutTightRangeThreshold();

        // Condition 2: Price near edge (within edgeProximityPct of range from high or low)
        double edgeZone = range * config.getBreakoutEdgeProximityPct();
        boolean nearHigh = spotPrice >= (highestHigh - edgeZone);
        boolean nearLow = spotPrice <= (lowestLow + edgeZone);
        boolean nearEdge = nearHigh || nearLow;

        // Condition 3: Momentum buildup — last N candles all in the same direction
        int momentumCandles = config.getBreakoutMomentumCandles();
        boolean momentumBuilding = false;
        if (candles.size() >= momentumCandles + 1) {
            int momStart = candles.size() - momentumCandles;
            boolean allUp = true;
            boolean allDown = true;

            for (int i = momStart; i < candles.size(); i++) {
                double currClose = candles.get(i).close;
                double prevClose = candles.get(i - 1).close;
                if (currClose < prevClose) allUp = false;    // strict: flat candle doesn't kill uptrend
                if (currClose > prevClose) allDown = false;   // strict: flat candle doesn't kill downtrend
            }
            momentumBuilding = allUp || allDown;
        }

        // Count how many breakout conditions are met
        int breakoutSignals = 0;
        if (tightRange) breakoutSignals++;
        if (nearEdge) breakoutSignals++;
        if (momentumBuilding) breakoutSignals++;

        BreakoutRisk risk;
        if (breakoutSignals >= 3) {
            risk = BreakoutRisk.HIGH;
        } else if (breakoutSignals >= 2) {
            risk = BreakoutRisk.MEDIUM;
        } else {
            risk = BreakoutRisk.LOW;
        }

        log.debug("V3 BREAKOUT: tightRange={} (rangeFrac={}), nearEdge={} (nearHigh={}, nearLow={}), " +
                        "momentum={}, signals={}, risk={}",
                tightRange, String.format("%.5f", rangeFraction),
                nearEdge, nearHigh, nearLow, momentumBuilding, breakoutSignals, risk);
        return risk;
    }

    // ==================================================================================
    //                       EXCESSIVE RANGE VETO GATE
    // ==================================================================================

    /**
     * Hard veto: checks if the recent candle range exceeds the excessive range threshold.
     *
     * <p>If the last N candles (default 10) have a combined high-low range exceeding
     * the configured fraction of the spot price (default 0.8% = ~192pts at NIFTY 24000),
     * a strong directional move is underway. This is unsafe for straddle entry regardless
     * of what regime signals show, because the move may continue.</p>
     *
     * <p>This gate catches strong trending moves that the regime layer's individual
     * signals might miss (e.g., a fast move where VWAP hasn't caught up yet).</p>
     *
     * <p>HFT: Single pass through candle array, primitive arithmetic.</p>
     *
     * @return true if range is excessive (VETO trade), false if safe
     */
    private boolean evaluateExcessiveRange(List<HistoricalData> candles, double spotPrice) {
        int required = config.getExcessiveRangeCandles();
        if (candles.size() < required) {
            log.debug("V3 EXCESSIVE_RANGE: insufficient candles ({}/{}), no veto", candles.size(), required);
            return false; // Insufficient data → don't veto
        }

        int startIdx = candles.size() - required;
        double highestHigh = Double.MIN_VALUE;
        double lowestLow = Double.MAX_VALUE;

        for (int i = startIdx; i < candles.size(); i++) {
            HistoricalData c = candles.get(i);
            if (c.high > highestHigh) highestHigh = c.high;
            if (c.low < lowestLow) lowestLow = c.low;
        }

        double rangeFraction = (spotPrice > 0) ? (highestHigh - lowestLow) / spotPrice : 0;
        boolean excessive = rangeFraction >= config.getExcessiveRangeThreshold();

        log.debug("V3 EXCESSIVE_RANGE: range={}, rangeFraction={}, threshold={}, candles={}, vetoed={}",
                String.format("%.2f", highestHigh - lowestLow), String.format("%.5f", rangeFraction),
                config.getExcessiveRangeThreshold(), required, excessive);
        return excessive;
    }

    // ==================================================================================
    //                       TIME-BASED ADAPTATION
    // ==================================================================================

    /**
     * Compute time-based final score adjustment (IST):
     * <ul>
     *   <li>09:15–10:00: −1 (opening session — higher volatility, stricter filtering)</li>
     *   <li>10:00–13:30: 0 (mid-session — normal market behaviour)</li>
     *   <li>13:30–15:00: +1 (pre-close — theta decay accelerates, more lenient)</li>
     * </ul>
     *
     * @return adjustment value (−1, 0, or +1)
     */
    int computeTimeAdjustment() {
        LocalTime now = getCurrentISTTime();
        if (!now.isBefore(MARKET_OPEN) && now.isBefore(OPENING_SESSION_END)) {
            return -1;  // Opening session — stricter
        }
        if (!now.isBefore(CLOSING_SESSION_START) && !now.isAfter(MARKET_CLOSE)) {
            return 1;   // Pre-close — more lenient
        }
        return 0;       // Mid-session — normal
    }


    // ==================================================================================
    //                       VWAP COMPUTATION (Shared)
    // ==================================================================================

    /**
     * Compute VWAP from candle data. Prefers MarketDataEngine pre-computed VWAP first.
     * Falls back to SMA of typical price when volume is unavailable (index instruments).
     *
     * @param candles        1-minute candles
     * @param instrumentType instrument for MDE cache lookup
     * @return VWAP value, or -1 if unavailable
     */
    private double computeVWAP(List<HistoricalData> candles, String instrumentType) {
        // Prefer pre-computed VWAP from MarketDataEngine (zero computation)
        try {
            Optional<BigDecimal> cachedVwap = marketDataEngine.getVWAP(instrumentType);
            if (cachedVwap.isPresent()) {
                double vwap = cachedVwap.get().doubleValue();
                if (vwap > 0) {
                    log.debug("V3 VWAP: using MDE cached value={} for {}", String.format("%.2f", vwap), instrumentType);
                    return vwap;
                }
            }
        } catch (Exception e) {
            log.debug("V3 VWAP: MDE cache miss for {}: {}", instrumentType, e.getMessage());
        }

        // Fallback: compute from candles (SMA proxy for index — volume unavailable)
        if (candles == null || candles.size() < 2) {
            return -1;
        }

        int count = Math.min(config.getVwapCandleCount(), candles.size());
        int startIdx = candles.size() - count;

        double sumTPxVol = 0;
        double sumVol = 0;
        double sumTP = 0;
        int used = 0;

        for (int i = startIdx; i < candles.size(); i++) {
            HistoricalData c = candles.get(i);
            double tp = (c.high + c.low + c.close) / 3.0;
            sumTPxVol += tp * c.volume;
            sumVol += c.volume;
            sumTP += tp;
            used++;
        }

        if (sumVol > 0) {
            return sumTPxVol / sumVol;
        } else if (used > 0) {
            // NIFTY 50 index — volume unavailable, SMA of typical price is the best proxy
            return sumTP / used;
        }
        return -1;
    }

    // ==================================================================================
    //                       DATA FETCHING HELPERS
    // ==================================================================================

    /**
     * Fetch spot price from MarketDataEngine cache (zero latency).
     * Falls back to direct API only if MDE is unavailable.
     */
    private double fetchSpotPrice(String instrumentType) {
        try {
            Optional<Double> cached = marketDataEngine.getIndexPrice(instrumentType);
            if (cached.isPresent()) {
                return cached.get();
            }
        } catch (Exception e) {
            log.debug("V3: MDE spot price miss for {}: {}", instrumentType, e.getMessage());
        }
        // Fallback to direct API (should rarely happen when MDE is running)
        try {
            String symbol = resolveSpotSymbol(instrumentType);
            var ltp = tradingService.getLTP(new String[]{symbol});
            if (ltp != null && ltp.containsKey(symbol)) {
                return ltp.get(symbol).lastPrice;
            }
        } catch (KiteException | IOException e) {
            log.warn("V3: Failed to fetch spot price for {} via API: {}", instrumentType, e.getMessage());
        }
        return -1;
    }

    /**
     * Fetch 1-minute candles from MarketDataEngine cache.
     * Falls back to direct API only if MDE is unavailable.
     */
    private List<HistoricalData> fetchOneMinuteCandles(String instrumentType) {
        try {
            Optional<List<HistoricalData>> cached = marketDataEngine.getCandles(instrumentType);
            if (cached.isPresent() && !cached.get().isEmpty()) {
                log.debug("V3: Using MDE cached 1-min candles for {}, count={}", instrumentType, cached.get().size());
                return cached.get();
            }
        } catch (Exception e) {
            log.debug("V3: MDE candle cache miss for {}: {}", instrumentType, e.getMessage());
        }

        // Fallback: direct API
        String instrumentToken = resolveInstrumentToken(instrumentType);
        int maxNeeded = Math.max(config.getRangeCompressionCandles(),
                Math.max(config.getOscillationCandleCount(),
                        Math.max(config.getMicroVwapPullbackCandles(),
                                Math.max(config.getMicroOscillationCandles(),
                                        Math.max(config.getMicroRangeCandles(),
                                                config.getBreakoutRangeCandles())))));
        return fetchCandlesFromAPI(instrumentToken, "minute", maxNeeded);
    }

    /**
     * Fetch candles for ADX computation. Prefers MDE cache for 1-min candles.
     */
    private List<HistoricalData> fetchADXCandles(String instrumentType, String instrumentToken) {
        String interval = config.getAdxCandleInterval();
        if ("minute".equals(interval)) {
            try {
                Optional<List<HistoricalData>> cached = marketDataEngine.getCandles(instrumentType);
                if (cached.isPresent() && cached.get().size() >= config.getAdxCandleCount()) {
                    return cached.get();
                }
            } catch (Exception e) {
                log.debug("V3: MDE candle cache miss for ADX {}: {}", instrumentType, e.getMessage());
            }
        }
        return fetchCandlesFromAPI(instrumentToken, interval, config.getAdxCandleCount());
    }

    /**
     * Fetch historical candles directly from Kite API.
     * Used only as fallback when MarketDataEngine cache misses.
     */
    private List<HistoricalData> fetchCandlesFromAPI(String instrumentToken, String interval, int candleCount) {
        try {
            int minutesPerCandle = parseIntervalMinutes(interval);
            int totalMinutes = minutesPerCandle * (candleCount + 5); // +5 buffer for partial candles

            ZonedDateTime now = ZonedDateTime.now(IST);
            ZonedDateTime from = now.minusMinutes(totalMinutes);

            // Clamp to market open — no candles before 09:15 IST
            ZonedDateTime todayOpen = now.toLocalDate().atTime(MARKET_OPEN).atZone(IST);
            if (from.isBefore(todayOpen)) {
                from = todayOpen;
            }

            Date fromDate = Date.from(from.toInstant());
            Date toDate = Date.from(now.toInstant());

            HistoricalData data = tradingService.getHistoricalData(
                    fromDate, toDate, instrumentToken, interval, false, false);

            if (data != null && data.dataArrayList != null && !data.dataArrayList.isEmpty()) {
                return data.dataArrayList;
            }
        } catch (KiteException | IOException e) {
            log.warn("V3: fetchCandlesFromAPI failed: token={}, interval={}: {}",
                    instrumentToken, interval, e.getMessage());
        }
        return Collections.emptyList();
    }

    // ==================================================================================
    //                       TIME & MARKET HELPERS
    // ==================================================================================

    /** Check if current IST time is within market trading hours. Package-private for testing. */
    boolean isWithinMarketHours() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        LocalTime time = now.toLocalTime();
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        return !time.isBefore(MARKET_OPEN) && !time.isAfter(MARKET_CLOSE);
    }

    /** Check if enough time elapsed since open for ADX. Package-private for testing. */
    boolean hasEnoughTimeForADX() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        long minutesSinceOpen = Duration.between(MARKET_OPEN, now.toLocalTime()).toMinutes();
        return minutesSinceOpen >= MIN_MINUTES_AFTER_OPEN_FOR_ADX;
    }

    /** Get current IST time. Package-private for testing. */
    LocalTime getCurrentISTTime() {
        return ZonedDateTime.now(IST).toLocalTime();
    }

    // ==================================================================================
    //                       INSTRUMENT RESOLUTION HELPERS
    // ==================================================================================

    private String resolveInstrumentToken(String instrumentType) {
        String key = instrumentType.toUpperCase();
        String cachedToken = instrumentTokenCache.get(key);
        if (cachedToken != null) {
            return cachedToken;
        }

        String tradingSymbol = switch (key) {
            case "NIFTY" -> "NIFTY 50";
            case "BANKNIFTY" -> "NIFTY BANK";
            default -> instrumentType;
        };

        try {
            List<Instrument> nseInstruments = instrumentCacheService.getInstruments("NSE");
            for (int i = 0; i < nseInstruments.size(); i++) {
                Instrument inst = nseInstruments.get(i);
                if (tradingSymbol.equals(inst.tradingsymbol)) {
                    String token = String.valueOf(inst.instrument_token);
                    instrumentTokenCache.put(key, token);
                    return token;
                }
            }
        } catch (KiteException | IOException e) {
            log.warn("V3: Failed to resolve instrument token for {}: {}", instrumentType, e.getMessage());
        }

        // Hardcoded fallback (known stable Kite instrument tokens)
        String fallbackToken = switch (key) {
            case "NIFTY" -> "256265";
            case "BANKNIFTY" -> "260105";
            default -> {
                log.error("V3: No fallback instrument token for unknown type: {}", instrumentType);
                yield "";
            }
        };
        instrumentTokenCache.put(key, fallbackToken);
        return fallbackToken;
    }

    private String resolveSpotSymbol(String instrumentType) {
        return switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> "NSE:NIFTY 50";
            case "BANKNIFTY" -> "NSE:NIFTY BANK";
            default -> throw new IllegalArgumentException("Unsupported instrument: " + instrumentType);
        };
    }

    private double getStrikeInterval(String instrumentType) {
        return switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> 50.0;
            case "BANKNIFTY" -> 100.0;
            default -> 50.0;
        };
    }

    private String getUnderlyingName(String instrumentType) {
        return switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> "NIFTY";
            case "BANKNIFTY" -> "BANKNIFTY";
            default -> instrumentType.toUpperCase();
        };
    }

    private int parseIntervalMinutes(String interval) {
        return switch (interval.toLowerCase()) {
            case "minute" -> 1;
            case "3minute" -> 3;
            case "5minute" -> 5;
            case "10minute" -> 10;
            case "15minute" -> 15;
            case "30minute" -> 30;
            case "60minute" -> 60;
            default -> 1;
        };
    }

    /**
     * Fast same-day check using epoch-day arithmetic.
     * HFT: Zero allocation — avoids Calendar.getInstance() overhead.
     */
    private boolean isSameDay(Date d1, Date d2) {
        long day1 = (d1.getTime() + IST_OFFSET_MS) / MS_PER_DAY;
        long day2 = (d2.getTime() + IST_OFFSET_MS) / MS_PER_DAY;
        return day1 == day2;
    }
}

