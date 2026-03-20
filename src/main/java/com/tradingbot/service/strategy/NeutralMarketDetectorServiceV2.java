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
import com.zerodhatech.models.Quote;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Neutral Market Detection Engine V2 — Weighted Confidence Scoring.
 *
 * <p>Redesign of the original {@link NeutralMarketDetectorService} to increase tradable
 * opportunities, eliminate over-filtering, and provide dynamic confidence-based regime
 * classification optimised for the SELL ATM STRADDLE intraday strategy on NIFTY 50.</p>
 *
 * <h2>Key Improvements over V1</h2>
 * <ul>
 *   <li>Weighted scoring (0–10) instead of binary +2 per signal</li>
 *   <li>Regime classification: STRONG_NEUTRAL / WEAK_NEUTRAL / TRENDING</li>
 *   <li>New Price Oscillation signal — detects choppy, range-bound price action</li>
 *   <li>New VWAP Pullback Entry signal — detects mean-reversion entry edge</li>
 *   <li>Removed lagging Premium Decay signal</li>
 *   <li>Gamma Pin only on expiry days (avoids noise on non-expiry days)</li>
 *   <li>Time-based score adaptation (strict opening, lenient pre-close)</li>
 *   <li>Confidence scaling for position sizing ({@link #getRecommendedLotMultiplier})</li>
 * </ul>
 *
 * <h2>Signal Weights</h2>
 * <table>
 *   <tr><th>Signal</th><th>Weight</th><th>Notes</th></tr>
 *   <tr><td>VWAP Proximity</td><td>3</td><td>Highest weight — core mean-reversion signal</td></tr>
 *   <tr><td>Range Compression</td><td>2</td><td>Tight range confirms low volatility</td></tr>
 *   <tr><td>Price Oscillation</td><td>2</td><td>Chop detection via direction reversals</td></tr>
 *   <tr><td>VWAP Pullback</td><td>2</td><td>Entry timing — price reverting to VWAP</td></tr>
 *   <tr><td>ADX Trend</td><td>1</td><td>Low weight — confirmatory only</td></tr>
 *   <tr><td>Gamma Pin</td><td>1</td><td>Expiry day only</td></tr>
 * </table>
 *
 * <h2>HFT Safety</h2>
 * <ul>
 *   <li>All price arithmetic uses {@code double} primitives — no BigDecimal on hot path</li>
 *   <li>Indexed {@code for} loops — no Iterator/Stream allocations</li>
 *   <li>Pre-allocated result objects where possible</li>
 *   <li>{@link AtomicReference} for cache — no synchronized blocks</li>
 *   <li>Market data read exclusively from {@link MarketDataEngine} cache</li>
 * </ul>
 *
 * @since 5.0
 * @see NeutralMarketConfig
 * @see NeutralMarketResult
 */
@Service
@Slf4j
public class NeutralMarketDetectorServiceV2 {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    // Time-based adaptation boundaries
    private static final LocalTime OPENING_SESSION_END = LocalTime.of(10, 0);
    private static final LocalTime CLOSING_SESSION_START = LocalTime.of(13, 30);

    // ADX warmup: need ~20 minutes of 1-min candles after open
    private static final int MIN_MINUTES_AFTER_OPEN_FOR_ADX = 20;

    // IST offset for fast day comparison (no Calendar allocation)
    private static final long IST_OFFSET_MS = 19800000L;
    private static final long MS_PER_DAY = 86400000L;

    private final NeutralMarketConfig config;
    private final MarketDataEngine marketDataEngine;
    private final TradingService tradingService;
    private final InstrumentCacheService instrumentCacheService;

    /** Per-instrument cached composite evaluation results with TTL. */
    private final ConcurrentHashMap<String, CachedResult> cachedResults = new ConcurrentHashMap<>(4);

    /** Instrument token cache to avoid repeated NSE instrument list scans. */
    private final ConcurrentHashMap<String, String> instrumentTokenCache = new ConcurrentHashMap<>(4);

    public NeutralMarketDetectorServiceV2(NeutralMarketConfig config,
                                          MarketDataEngine marketDataEngine,
                                          TradingService tradingService,
                                          InstrumentCacheService instrumentCacheService) {
        this.config = config;
        this.marketDataEngine = marketDataEngine;
        this.tradingService = tradingService;
        this.instrumentCacheService = instrumentCacheService;
    }

    // ==================== INTERNAL CACHE RECORD ====================

    private record CachedResult(NeutralMarketResult result, long fetchTimeMs, String instrumentType) {
        boolean isExpired(long ttlMs) {
            return System.currentTimeMillis() - fetchTimeMs > ttlMs;
        }
    }

    // ==================== PUBLIC API ====================

    /**
     * Evaluate all neutral market signals for the given instrument.
     *
     * @param instrumentType "NIFTY" or "BANKNIFTY"
     * @return composite result with weighted scoring and regime classification
     */
    public NeutralMarketResult evaluate(String instrumentType) {
        if (!config.isEnabled()) {
            log.debug("Neutral market filter V2 disabled, allowing trade");
            return NeutralMarketResult.disabled();
        }

        // Check per-instrument cache
        String cacheKey = instrumentType.toUpperCase();
        CachedResult cached = cachedResults.get(cacheKey);
        if (cached != null && !cached.isExpired(config.getCacheTtlMs())) {
            log.debug("Returning cached neutral market V2 result: score={}/{}, regime={}",
                    cached.result().totalScore(), cached.result().maxScore(), cached.result().getRegime());
            return cached.result();
        }

        // Evaluate fresh
        try {
            NeutralMarketResult result = evaluateAllSignals(instrumentType);
            cachedResults.put(cacheKey, new CachedResult(result, System.currentTimeMillis(), instrumentType));
            return result;
        } catch (Exception e) {
            log.error("Neutral market V2 evaluation failed: {}", e.getMessage(), e);
            boolean allow = config.isAllowOnDataUnavailable();
            NeutralMarketResult fallback = NeutralMarketResult.dataUnavailable(
                    allow, e.getMessage(), config.getWeakNeutralThreshold());
            // Cache error results with a shorter TTL (5s) to allow faster recovery
            long errorCacheTimeOffset = Math.max(0, config.getCacheTtlMs() - 5000L);
            cachedResults.put(cacheKey, new CachedResult(fallback,
                    System.currentTimeMillis() - errorCacheTimeOffset, instrumentType));
            return fallback;
        }
    }

    /**
     * Convenience: check if the market is neutral (tradable) for the given instrument.
     */
    public boolean isMarketNeutral(String instrumentType) {
        return evaluate(instrumentType).neutral();
    }

    /**
     * Convenience: return the raw neutral score (0–10).
     */
    public int calculateNeutralScore(String instrumentType) {
        return evaluate(instrumentType).totalScore();
    }

    /**
     * Clear all cached state. Useful for testing or forced refresh.
     */
    public void clearCache() {
        cachedResults.clear();
        log.debug("Neutral market detector V2 cache cleared");
    }

    /**
     * Recommend lot multiplier based on confidence score.
     *
     * <ul>
     *   <li>score ≥ 8 → 3x (high confidence)</li>
     *   <li>score ≥ 6 → 2x (moderate confidence)</li>
     *   <li>score ≥ 4 → 1x (low confidence)</li>
     *   <li>score &lt; 4 → 0 (not tradable)</li>
     * </ul>
     *
     * @param score the neutral market score (0–10)
     * @return recommended lot multiplier
     */
    public int getRecommendedLotMultiplier(int score) {
        if (score >= 8) return 3;
        if (score >= 6) return 2;
        if (score >= 4) return 1;
        return 0;
    }

    // ==================== CORE EVALUATION ====================

    /**
     * Check if current IST time is within market trading hours.
     * Package-private for test override.
     */
    boolean isWithinMarketHours() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        LocalTime time = now.toLocalTime();
        DayOfWeek day = now.getDayOfWeek();

        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        return !time.isBefore(MARKET_OPEN) && !time.isAfter(MARKET_CLOSE);
    }

    /**
     * Check if enough time has elapsed since market open for ADX candles.
     * Package-private for test override.
     */
    boolean hasEnoughTimeForADX() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        long minutesSinceOpen = Duration.between(MARKET_OPEN, now.toLocalTime()).toMinutes();
        return minutesSinceOpen >= MIN_MINUTES_AFTER_OPEN_FOR_ADX;
    }

    /**
     * Get the current IST time. Package-private for test override.
     */
    LocalTime getCurrentISTTime() {
        return ZonedDateTime.now(IST).toLocalTime();
    }

    private NeutralMarketResult evaluateAllSignals(String instrumentType) {
        log.info("Evaluating neutral market V2 signals for {}", instrumentType);
        long startTime = System.currentTimeMillis();

        // ==================== MARKET HOURS GUARD ====================
        if (!isWithinMarketHours()) {
            ZonedDateTime now = ZonedDateTime.now(IST);
            log.warn("Neutral market V2 evaluation skipped: outside market hours. time={}, day={}",
                    now.toLocalTime(), now.getDayOfWeek());
            boolean allow = config.isAllowOnDataUnavailable();
            return NeutralMarketResult.dataUnavailable(allow,
                    "Outside market hours (" + now.toLocalTime() + " IST, " + now.getDayOfWeek() + ")",
                    config.getWeakNeutralThreshold());
        }

        // ==================== FETCH SPOT PRICE ====================
        double spotPrice = fetchSpotPrice(instrumentType);
        if (spotPrice <= 0) {
            log.warn("Could not fetch spot price for {}", instrumentType);
            boolean allow = config.isAllowOnDataUnavailable();
            return NeutralMarketResult.dataUnavailable(allow, "Spot price unavailable",
                    config.getWeakNeutralThreshold());
        }

        // ==================== FETCH SHARED DATA ====================
        double strikeInterval = getStrikeInterval(instrumentType);

        // Fetch 1-minute candles once — shared by VWAP, Range Compression, Oscillation, Pullback
        List<HistoricalData> oneMinCandles = fetchOneMinuteCandles(instrumentType);

        // Compute VWAP once — shared by VWAP Proximity and VWAP Pullback signals
        double vwap = computeVWAP(oneMinCandles, instrumentType);

        // Determine if expiry day — controls Gamma Pin signal inclusion
        // Compute today's epoch day once — avoid repeated new Date() allocations
        long todayEpochDay = (startTime + IST_OFFSET_MS) / MS_PER_DAY;
        String underlyingName = getUnderlyingName(instrumentType);
        boolean isExpiryDay = false;
        List<Instrument> nfoInstruments = Collections.emptyList();
        Date nearestExpiry = null;
        try {
            // Prefer MarketDataEngine cached expiry first
            Optional<Date> cachedExpiry = marketDataEngine.getNearestWeeklyExpiry(instrumentType);
            if (cachedExpiry.isPresent()) {
                nearestExpiry = cachedExpiry.get();
                isExpiryDay = ((nearestExpiry.getTime() + IST_OFFSET_MS) / MS_PER_DAY) == todayEpochDay;
            }
        } catch (Exception e) {
            log.debug("MDE expiry cache miss for {}: {}", instrumentType, e.getMessage());
        }

        // Fallback: scan NFO instruments if MDE didn't provide expiry
        if (nearestExpiry == null) {
            try {
                nfoInstruments = instrumentCacheService.getInstruments("NFO");
                nearestExpiry = findNearestExpiry(nfoInstruments, underlyingName);
                if (nearestExpiry != null) {
                    isExpiryDay = ((nearestExpiry.getTime() + IST_OFFSET_MS) / MS_PER_DAY) == todayEpochDay;
                }
            } catch (KiteException | IOException e) {
                log.warn("Failed to fetch NFO instruments for expiry check: {}", e.getMessage());
            }
        } else if (isExpiryDay) {
            // Need NFO instruments for Gamma Pin evaluation on expiry day
            try {
                nfoInstruments = instrumentCacheService.getInstruments("NFO");
            } catch (KiteException | IOException e) {
                log.warn("Failed to fetch NFO instruments for gamma pin: {}", e.getMessage());
            }
        }

        // ==================== EVALUATE SIGNALS ====================
        // Pre-allocate list — max 6 signals (5 core + 1 expiry-only)
        int signalCapacity = isExpiryDay ? 6 : 5;
        List<SignalResult> signals = new ArrayList<>(signalCapacity);
        Map<String, Boolean> breakdown = new LinkedHashMap<>(signalCapacity);

        // Signal 1: VWAP Proximity (weight: configurable, default 3)
        SignalResult vwapSignal = evaluateVWAPProximity(spotPrice, vwap);
        signals.add(vwapSignal);
        breakdown.put(vwapSignal.name(), vwapSignal.passed());

        // Signal 2: Range Compression (weight: configurable, default 2)
        // Reuse already-computed isExpiryDay flag — no redundant isSameDay call
        SignalResult rangeSignal = evaluateRangeCompression(spotPrice, oneMinCandles, isExpiryDay);
        signals.add(rangeSignal);
        breakdown.put(rangeSignal.name(), rangeSignal.passed());

        // Signal 3: Price Oscillation — NEW (weight: configurable, default 2)
        SignalResult oscillationSignal = evaluatePriceOscillation(oneMinCandles);
        signals.add(oscillationSignal);
        breakdown.put(oscillationSignal.name(), oscillationSignal.passed());

        // Signal 4: VWAP Pullback Entry — NEW (weight: configurable, default 2)
        SignalResult pullbackSignal = evaluateVWAPPullback(oneMinCandles, vwap);
        signals.add(pullbackSignal);
        breakdown.put(pullbackSignal.name(), pullbackSignal.passed());

        // Signal 5: ADX Trend Strength (weight: configurable, default 1)
        SignalResult adxSignal = evaluateADX(instrumentType);
        signals.add(adxSignal);
        breakdown.put(adxSignal.name(), adxSignal.passed());

        // Signal 6: Gamma Pin (weight: configurable, default 1) — EXPIRY DAY ONLY
        if (isExpiryDay) {
            SignalResult gammaSignal = evaluateGammaPin(spotPrice, instrumentType, strikeInterval,
                    nfoInstruments, nearestExpiry);
            signals.add(gammaSignal);
            breakdown.put(gammaSignal.name(), gammaSignal.passed());
        }

        // ==================== AGGREGATE SCORE ====================
        int rawScore = 0;
        for (int i = 0; i < signals.size(); i++) {
            rawScore += signals.get(i).score();
        }

        // ==================== TIME-BASED ADAPTATION ====================
        int timeAdjustment = 0;
        if (config.isTimeBasedAdaptationEnabled()) {
            timeAdjustment = computeTimeAdjustment();
        }
        int adjustedScore = Math.max(0, Math.min(rawScore + timeAdjustment, NeutralMarketResult.MAX_SCORE));

        // ==================== REGIME CLASSIFICATION ====================
        String regime;
        boolean tradable;
        if (adjustedScore >= config.getStrongNeutralThreshold()) {
            regime = NeutralMarketResult.REGIME_STRONG_NEUTRAL;
            tradable = true;
        } else if (adjustedScore >= config.getWeakNeutralThreshold()) {
            regime = NeutralMarketResult.REGIME_WEAK_NEUTRAL;
            tradable = true;
        } else {
            regime = NeutralMarketResult.REGIME_TRENDING;
            tradable = false;
        }

        // ==================== CONFIDENCE ====================
        double confidence = (double) adjustedScore / NeutralMarketResult.MAX_SCORE;

        // ==================== SUMMARY ====================
        StringBuilder sb = new StringBuilder(256);
        for (int i = 0; i < signals.size(); i++) {
            SignalResult s = signals.get(i);
            if (i > 0) sb.append(' ');
            sb.append(s.passed() ? '\u2713' : '\u2717').append(' ').append(s.name())
              .append('(').append(s.score()).append('/').append(s.maxScore()).append(')');
        }
        if (timeAdjustment != 0) {
            sb.append(" [timeAdj=").append(timeAdjustment > 0 ? "+" : "").append(timeAdjustment).append(']');
        }
        String summary = sb.toString();

        long elapsed = System.currentTimeMillis() - startTime;

        // ==================== STRUCTURED LOGGING ====================
        // Per-signal detail at DEBUG to avoid log flooding (12+ lines per 30s cycle)
        if (log.isDebugEnabled()) {
            log.debug("Neutral V2 score: instrument={}, rawScore={}, timeAdj={}, adjustedScore={}, regime={}, tradable={}",
                    instrumentType, rawScore, timeAdjustment, adjustedScore, regime, tradable);

            for (int i = 0; i < signals.size(); i++) {
                SignalResult s = signals.get(i);
                log.debug("Signal V2: name={}, score={}/{}, passed={}, detail=[{}]",
                        s.name(), s.score(), s.maxScore(), s.passed(), s.detail());
            }
        }

        // Single summary line at INFO — includes all essential data
        log.info("Neutral V2 summary: instrument={}, price={}, signals=[{}], score={}/{}, regime={}, " +
                        "confidence={}, tradable={}, lotMultiplier={}, elapsedMs={}",
                instrumentType, spotPrice, summary,
                adjustedScore, NeutralMarketResult.MAX_SCORE, regime,
                confidence, tradable,
                getRecommendedLotMultiplier(adjustedScore), elapsed);

        return new NeutralMarketResult(tradable, adjustedScore, confidence, regime,
                breakdown, signals, summary, Instant.ofEpochMilli(startTime), config.getWeakNeutralThreshold());
    }

    // ==================== SIGNAL 1: VWAP PROXIMITY (Weight: 3) ====================

    /**
     * Measure percentage deviation from VWAP. Highest-weight signal because
     * VWAP proximity is the strongest predictor of neutral, mean-reverting markets
     * for ATM straddle strategies.
     *
     * @param spotPrice current spot price
     * @param vwap      computed VWAP value
     * @return SignalResult with score = weight if deviation < threshold
     */
    private SignalResult evaluateVWAPProximity(double spotPrice, double vwap) {
        final String signalName = "VWAP_PROXIMITY";
        final int weight = config.getWeightVwap();

        try {
            if (vwap <= 0) {
                return SignalResult.unavailable(signalName, weight, "VWAP not available");
            }

            double deviation = Math.abs(spotPrice - vwap) / vwap;
            double deviationPct = deviation * 100.0;
            double thresholdPct = config.getVwapDeviationThreshold() * 100.0;
            boolean passed = deviation < config.getVwapDeviationThreshold();

            log.debug("VWAP proximity: price={}, vwap={}, deviationPct={}, threshold={}, passed={}",
                    spotPrice, vwap, deviationPct, thresholdPct, passed);

            String detail = String.format("VWAP=%.2f, Spot=%.2f, Deviation=%.4f%% (threshold=%.4f%%)",
                    vwap, spotPrice, deviationPct, thresholdPct);
            return passed ? SignalResult.passed(signalName, weight, detail)
                          : SignalResult.failed(signalName, weight, detail);

        } catch (Exception e) {
            log.warn("Signal {} evaluation failed: {}", signalName, e.getMessage());
            return SignalResult.unavailable(signalName, weight, e.getMessage());
        }
    }

    // ==================== SIGNAL 2: RANGE COMPRESSION (Weight: 2) ====================

    /**
     * Check if the last N candles are compressed within a tight range.
     * Smaller range = lower volatility = better for straddle selling.
     *
     * @param spotPrice    current spot price
     * @param candles      1-minute candles
     * @param isExpiryDay  uses tighter threshold on expiry
     * @return SignalResult with score = weight if range < threshold
     */
    private SignalResult evaluateRangeCompression(double spotPrice, List<HistoricalData> candles,
                                                   boolean isExpiryDay) {
        final String signalName = "RANGE_COMPRESSION";
        final int weight = config.getWeightRange();

        try {
            int required = config.getRangeCompressionCandles();
            if (candles == null || candles.size() < required) {
                return SignalResult.unavailable(signalName, weight,
                        "Insufficient candles: need " + required + ", got " + (candles == null ? 0 : candles.size()));
            }

            double effectiveThreshold = isExpiryDay
                    ? config.getExpiryDayRangeThreshold()
                    : config.getRangeCompressionThreshold();

            int startIdx = candles.size() - required;
            double highestHigh = Double.MIN_VALUE;
            double lowestLow = Double.MAX_VALUE;

            for (int i = startIdx; i < candles.size(); i++) {
                HistoricalData candle = candles.get(i);
                if (candle.high > highestHigh) highestHigh = candle.high;
                if (candle.low < lowestLow) lowestLow = candle.low;
            }

            double range = highestHigh - lowestLow;
            double rangeFraction = range / spotPrice;
            double rangePct = rangeFraction * 100.0;
            double thresholdPct = effectiveThreshold * 100.0;
            boolean passed = rangeFraction < effectiveThreshold;

            log.debug("Range compression: rangePoints={}, rangePct={}, threshold={}, candles={}, expiry={}, passed={}",
                    range, rangePct, thresholdPct, required, isExpiryDay, passed);

            String detail = String.format("Range=%.2f (H=%.2f, L=%.2f), RangePct=%.4f%% (threshold=%.4f%%), Candles=%d",
                    range, highestHigh, lowestLow, rangePct, thresholdPct, required);
            return passed ? SignalResult.passed(signalName, weight, detail)
                          : SignalResult.failed(signalName, weight, detail);

        } catch (Exception e) {
            log.warn("Signal {} evaluation failed: {}", signalName, e.getMessage());
            return SignalResult.unavailable(signalName, weight, e.getMessage());
        }
    }

    // ==================== SIGNAL 3: PRICE OSCILLATION (Weight: 2) ====================

    /**
     * Detect choppy/oscillating price action by counting direction reversals
     * in the last N candles (close-to-close). A reversal occurs when the current candle
     * closes in the opposite direction of the previous candle.
     *
     * <p>High oscillation count = choppy market = favourable for straddle selling.
     * This signal replaces the lagging Premium Decay signal from V1.</p>
     *
     * <h3>HFT Safety</h3>
     * Uses indexed for loop with primitive double comparisons. Zero allocations.
     *
     * @param candles 1-minute candles
     * @return SignalResult with score = weight if reversals >= minimum threshold
     */
    private SignalResult evaluatePriceOscillation(List<HistoricalData> candles) {
        final String signalName = "PRICE_OSCILLATION";
        final int weight = config.getWeightOscillation();

        try {
            int required = config.getOscillationCandleCount();
            if (candles == null || candles.size() < required) {
                return SignalResult.unavailable(signalName, weight,
                        "Insufficient candles: need " + required + ", got " + (candles == null ? 0 : candles.size()));
            }

            int startIdx = candles.size() - required;
            int reversals = 0;
            // Track direction: +1 = up, -1 = down, 0 = flat
            int previousDirection = 0;

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

                // Count reversal: direction changed from positive to negative or vice versa
                if (direction != 0 && previousDirection != 0 && direction != previousDirection) {
                    reversals++;
                }

                if (direction != 0) {
                    previousDirection = direction;
                }
            }

            int minReversals = config.getOscillationMinReversals();
            boolean passed = reversals >= minReversals;
            int maxPossibleReversals = required - 2; // Theoretical max = N-2 alternating

            log.debug("Price oscillation: reversals={}, minRequired={}, maxPossible={}, candles={}, passed={}",
                    reversals, minReversals, maxPossibleReversals, required, passed);

            String detail = String.format("Reversals=%d (min=%d, max=%d), Candles=%d",
                    reversals, minReversals, maxPossibleReversals, required);
            return passed ? SignalResult.passed(signalName, weight, detail)
                          : SignalResult.failed(signalName, weight, detail);

        } catch (Exception e) {
            log.warn("Signal {} evaluation failed: {}", signalName, e.getMessage());
            return SignalResult.unavailable(signalName, weight, e.getMessage());
        }
    }

    // ==================== SIGNAL 4: VWAP PULLBACK ENTRY (Weight: 2) ====================

    /**
     * Detect a VWAP pullback entry pattern: price deviates from VWAP then reverts back.
     *
     * <p>Pattern detection in the last N candles:
     * <ol>
     *   <li>Find a candle where close deviated from VWAP by more than {@code vwapPullbackThreshold}</li>
     *   <li>Find a subsequent candle where close reverted to within {@code vwapPullbackReversionThreshold} of VWAP</li>
     * </ol>
     * If both legs found in sequence, the pullback pattern is confirmed — price is mean-reverting.</p>
     *
     * <h3>HFT Safety</h3>
     * Single pass through candle array with primitive comparisons. No allocations.
     *
     * @param candles 1-minute candles
     * @param vwap    pre-computed VWAP value
     * @return SignalResult with score = weight if pullback pattern detected
     */
    private SignalResult evaluateVWAPPullback(List<HistoricalData> candles, double vwap) {
        final String signalName = "VWAP_PULLBACK";
        final int weight = config.getWeightVwapPullback();

        try {
            if (vwap <= 0) {
                return SignalResult.unavailable(signalName, weight, "VWAP not available for pullback check");
            }

            int windowSize = config.getVwapPullbackCandleCount();
            if (candles == null || candles.size() < windowSize) {
                return SignalResult.unavailable(signalName, weight,
                        "Insufficient candles: need " + windowSize + ", got " + (candles == null ? 0 : candles.size()));
            }

            int startIdx = candles.size() - windowSize;
            double pullbackThreshold = config.getVwapPullbackThreshold();
            double reversionThreshold = config.getVwapPullbackReversionThreshold();

            // Phase 1: Find deviation candle
            int deviationIdx = -1;
            double maxDeviation = 0;

            for (int i = startIdx; i < candles.size(); i++) {
                double closePrice = candles.get(i).close;
                double deviation = Math.abs(closePrice - vwap) / vwap;
                if (deviation >= pullbackThreshold) {
                    if (deviationIdx == -1 || deviation > maxDeviation) {
                        deviationIdx = i;
                        maxDeviation = deviation;
                    }
                }
            }

            // Phase 2: Find reversion candle after the deviation
            boolean reversionFound = false;
            double reversionDeviation = Double.MAX_VALUE;

            if (deviationIdx >= 0) {
                for (int i = deviationIdx + 1; i < candles.size(); i++) {
                    double closePrice = candles.get(i).close;
                    double deviation = Math.abs(closePrice - vwap) / vwap;
                    if (deviation <= reversionThreshold) {
                        reversionFound = true;
                        reversionDeviation = deviation;
                        break;
                    }
                }
            }

            boolean passed = deviationIdx >= 0 && reversionFound;

            log.debug("VWAP pullback: deviationFound={}, maxDevPct={}, reversionFound={}, " +
                            "reversionDevPct={}, pullbackThreshold={}, reversionThreshold={}, passed={}",
                    deviationIdx >= 0, maxDeviation * 100,
                    reversionFound, reversionDeviation * 100,
                    pullbackThreshold * 100,
                    reversionThreshold * 100, passed);

            String detail;
            if (deviationIdx >= 0) {
                detail = String.format("Deviation=%.4f%% at candle %d, Reversion=%s (%.4f%%)",
                        maxDeviation * 100, deviationIdx - (candles.size() - windowSize),
                        reversionFound ? "YES" : "NO",
                        reversionDeviation == Double.MAX_VALUE ? 0 : reversionDeviation * 100);
            } else {
                detail = "No deviation from VWAP detected in window";
            }

            return passed ? SignalResult.passed(signalName, weight, detail)
                          : SignalResult.failed(signalName, weight, detail);

        } catch (Exception e) {
            log.warn("Signal {} evaluation failed: {}", signalName, e.getMessage());
            return SignalResult.unavailable(signalName, weight, e.getMessage());
        }
    }

    // ==================== SIGNAL 5: ADX TREND STRENGTH (Weight: 1) ====================

    /**
     * Compute ADX and check if below threshold (indicating range-bound market).
     * Low weight because ADX is a lagging indicator; used only as confirmation.
     *
     * <p>Uses the same Wilder-smoothed ADX computation as V1, but with reduced influence
     * on the final score to prevent over-filtering.</p>
     *
     * @param instrumentType "NIFTY" or "BANKNIFTY"
     * @return SignalResult with score = weight if ADX < threshold
     */
    private SignalResult evaluateADX(String instrumentType) {
        final String signalName = "ADX_TREND";
        final int weight = config.getWeightAdx();

        try {
            if (!hasEnoughTimeForADX()) {
                log.debug("ADX V2: UNAVAILABLE — insufficient time since market open, need {}min",
                        MIN_MINUTES_AFTER_OPEN_FOR_ADX);
                return SignalResult.unavailable(signalName, weight,
                        String.format("Early session: need %dmin since open", MIN_MINUTES_AFTER_OPEN_FOR_ADX));
            }

            // Fetch candles for ADX — prefer MarketDataEngine, fallback to API
            String instrumentToken = resolveInstrumentToken(instrumentType);
            List<HistoricalData> candles = fetchADXCandles(instrumentType, instrumentToken);

            if (candles == null || candles.size() < config.getAdxPeriod() * 2 + 1) {
                return SignalResult.unavailable(signalName, weight,
                        "Insufficient candles for ADX: need " + (config.getAdxPeriod() * 2 + 1)
                                + ", got " + (candles == null ? 0 : candles.size()));
            }

            double[] adxValues = NeutralMarketDetectorService.computeADXSeries(candles, config.getAdxPeriod());
            if (adxValues == null || adxValues.length == 0) {
                return SignalResult.unavailable(signalName, weight, "ADX computation returned no values");
            }

            double latestADX = adxValues[adxValues.length - 1];
            boolean passed = latestADX < config.getAdxThreshold();

            log.debug("ADX V2: adx={}, threshold={}, passed={}",
                    latestADX, config.getAdxThreshold(), passed);

            String detail = String.format("ADX=%.2f (threshold=%.2f)", latestADX, config.getAdxThreshold());
            return passed ? SignalResult.passed(signalName, weight, detail)
                          : SignalResult.failed(signalName, weight, detail);

        } catch (Exception e) {
            log.warn("Signal {} evaluation failed: {}", signalName, e.getMessage());
            return SignalResult.unavailable(signalName, weight, e.getMessage());
        }
    }

    // ==================== SIGNAL 6: GAMMA PIN (Weight: 1, Expiry Day Only) ====================

    /**
     * Detect if spot price is pinned near the strike with maximum combined OI.
     * Only evaluated on expiry days where gamma exposure causes price pinning.
     *
     * @param spotPrice      current spot price
     * @param instrumentType "NIFTY" or "BANKNIFTY"
     * @param strikeInterval strike interval (50 for NIFTY)
     * @param nfoInstruments NFO instruments (pre-fetched)
     * @param nearestExpiry  nearest expiry date
     * @return SignalResult with score = weight if spot is near max OI strike
     */
    private SignalResult evaluateGammaPin(double spotPrice, String instrumentType, double strikeInterval,
                                           List<Instrument> nfoInstruments, Date nearestExpiry) {
        final String signalName = "GAMMA_PIN";
        final int weight = config.getWeightGammaPin();

        try {
            double atmStrike = Math.round(spotPrice / strikeInterval) * strikeInterval;
            int strikesAround = config.getStrikesAroundAtm();
            String underlyingName = getUnderlyingName(instrumentType);

            if (nfoInstruments == null || nfoInstruments.isEmpty()) {
                return SignalResult.unavailable(signalName, weight, "NFO instruments not available");
            }
            if (nearestExpiry == null) {
                return SignalResult.unavailable(signalName, weight, "Could not determine nearest expiry");
            }

            // Build set of target strikes around ATM
            double[] targetStrikes = new double[2 * strikesAround + 1];
            for (int i = -strikesAround; i <= strikesAround; i++) {
                targetStrikes[i + strikesAround] = atmStrike + i * strikeInterval;
            }

            // Map strike index -> [CE symbol, PE symbol]
            String[][] strikeSymbols = new String[targetStrikes.length][2];

            // Scan NFO instruments to match target strikes and expiry
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

                // Find matching target strike index
                for (int j = 0; j < targetStrikes.length; j++) {
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
            List<String> allSymbols = new ArrayList<>(targetStrikes.length * 2);
            int[] symbolStrikeIndex = new int[targetStrikes.length * 2]; // index -> strike array index
            int symbolCount = 0;

            for (int j = 0; j < targetStrikes.length; j++) {
                if (strikeSymbols[j][0] != null) {
                    allSymbols.add(strikeSymbols[j][0]);
                    symbolStrikeIndex[symbolCount++] = j;
                }
                if (strikeSymbols[j][1] != null) {
                    allSymbols.add(strikeSymbols[j][1]);
                    symbolStrikeIndex[symbolCount++] = j;
                }
            }

            if (allSymbols.isEmpty()) {
                return SignalResult.unavailable(signalName, weight, "No option symbols found for OI fetch");
            }

            // Batch fetch quotes (contains OI)
            Map<String, Quote> quotes = tradingService.getQuote(allSymbols.toArray(new String[0]));
            if (quotes == null || quotes.isEmpty()) {
                return SignalResult.unavailable(signalName, weight, "Quote fetch returned empty");
            }

            // Aggregate OI per strike
            double[] combinedOI = new double[targetStrikes.length];
            for (int j = 0; j < targetStrikes.length; j++) {
                if (strikeSymbols[j][0] != null) {
                    Quote q = quotes.get(strikeSymbols[j][0]);
                    if (q != null) combinedOI[j] += q.oi;
                }
                if (strikeSymbols[j][1] != null) {
                    Quote q = quotes.get(strikeSymbols[j][1]);
                    if (q != null) combinedOI[j] += q.oi;
                }
            }

            // Find max OI strike
            double maxOIStrike = atmStrike;
            double maxOI = 0;
            for (int j = 0; j < targetStrikes.length; j++) {
                if (combinedOI[j] > maxOI) {
                    maxOI = combinedOI[j];
                    maxOIStrike = targetStrikes[j];
                }
            }

            double distance = Math.abs(spotPrice - maxOIStrike) / spotPrice;
            double distancePct = distance * 100.0;
            double thresholdPct = config.getGammaPinThreshold() * 100.0;
            boolean passed = distance < config.getGammaPinThreshold();

            log.debug("Gamma pin V2: price={}, maxOIStrike={}, maxOI={}, distancePct={}, threshold={}, passed={}",
                    spotPrice, maxOIStrike, maxOI, distancePct, thresholdPct, passed);

            String detail = String.format("MaxOI strike=%.0f (OI=%.0f), Distance=%.4f%% (threshold=%.4f%%)",
                    maxOIStrike, maxOI, distancePct, thresholdPct);
            return passed ? SignalResult.passed(signalName, weight, detail)
                          : SignalResult.failed(signalName, weight, detail);

        // KiteException extends Throwable (not Exception) in Kite SDK — must catch separately
        } catch (Exception | KiteException e) {
            log.warn("Signal {} evaluation failed: {}", signalName, e.getMessage());
            return SignalResult.unavailable(signalName, weight, e.getMessage());
        }
    }

    // ==================== TIME-BASED ADAPTATION ====================

    /**
     * Compute time-based score adjustment.
     * <ul>
     *   <li>09:15–10:00 (opening session): -1 (stricter, opening volatility)</li>
     *   <li>10:00–13:30 (mid-session): 0 (normal)</li>
     *   <li>13:30–15:00 (pre-close): +1 (more lenient, theta decay accelerates)</li>
     * </ul>
     *
     * @return score adjustment (-1, 0, or +1)
     */
    int computeTimeAdjustment() {
        LocalTime now = getCurrentISTTime();

        if (!now.isBefore(MARKET_OPEN) && now.isBefore(OPENING_SESSION_END)) {
            return -1; // Opening session — stricter
        }
        if (!now.isBefore(CLOSING_SESSION_START) && !now.isAfter(MARKET_CLOSE)) {
            return 1; // Pre-close session — more lenient
        }
        return 0; // Mid-session — normal
    }

    // ==================== VWAP COMPUTATION (Shared) ====================

    /**
     * Compute VWAP from candle data. Uses volume-weighted average if volume is available,
     * otherwise falls back to simple moving average of typical price.
     *
     * <p>Prefers MarketDataEngine cached VWAP first to avoid redundant computation.</p>
     *
     * @param candles        1-minute candles
     * @param instrumentType instrument for MDE cache lookup
     * @return VWAP value, or -1 if unavailable
     */
    private double computeVWAP(List<HistoricalData> candles, String instrumentType) {
        // Prefer pre-computed VWAP from MarketDataEngine
        try {
            Optional<BigDecimal> cachedVwap = marketDataEngine.getVWAP(instrumentType);
            if (cachedVwap.isPresent()) {
                double vwap = cachedVwap.get().doubleValue();
                if (vwap > 0) {
                    log.debug("Using MarketDataEngine cached VWAP for {}: {}", instrumentType, vwap);
                    return vwap;
                }
            }
        } catch (Exception e) {
            log.debug("MDE VWAP cache miss for {}: {}", instrumentType, e.getMessage());
        }

        // Fallback: compute from candles
        if (candles == null || candles.size() < 2) {
            return -1;
        }

        int count = Math.min(config.getVwapCandleCount(), candles.size());
        int startIdx = candles.size() - count;

        double sumTPxVol = 0.0;
        double sumVol = 0.0;
        double sumTP = 0.0;
        int candlesUsed = 0;

        for (int i = startIdx; i < candles.size(); i++) {
            HistoricalData candle = candles.get(i);
            double tp = (candle.high + candle.low + candle.close) / 3.0;
            double volume = candle.volume;
            sumTPxVol += tp * volume;
            sumVol += volume;
            sumTP += tp;
            candlesUsed++;
        }

        if (sumVol > 0) {
            return sumTPxVol / sumVol;
        } else if (candlesUsed > 0) {
            // NIFTY 50 index — volume unavailable, use SMA proxy
            return sumTP / candlesUsed;
        }
        return -1;
    }

    // ==================== DATA FETCHING HELPERS ====================

    /**
     * Fetch spot price from MarketDataEngine cache.
     * Falls back to direct API if MDE is unavailable.
     */
    private double fetchSpotPrice(String instrumentType) {
        try {
            Optional<Double> cached = marketDataEngine.getIndexPrice(instrumentType);
            if (cached.isPresent()) {
                return cached.get();
            }
        } catch (Exception e) {
            log.debug("MDE spot price cache miss for {}: {}", instrumentType, e.getMessage());
        }

        // Fallback to direct API
        try {
            String symbol = resolveSpotSymbol(instrumentType);
            var ltp = tradingService.getLTP(new String[]{symbol});
            if (ltp != null && ltp.containsKey(symbol)) {
                return ltp.get(symbol).lastPrice;
            }
        } catch (KiteException | IOException e) {
            log.warn("Failed to fetch spot price for {}: {}", instrumentType, e.getMessage());
        }
        return -1;
    }

    /**
     * Fetch 1-minute candles from MarketDataEngine cache.
     */
    private List<HistoricalData> fetchOneMinuteCandles(String instrumentType) {
        try {
            Optional<List<HistoricalData>> cached = marketDataEngine.getCandles(instrumentType);
            if (cached.isPresent() && !cached.get().isEmpty()) {
                log.debug("Using MDE cached 1-min candles for {}, count={}", instrumentType, cached.get().size());
                return cached.get();
            }
        } catch (Exception e) {
            log.debug("MDE candle cache miss for {}: {}", instrumentType, e.getMessage());
        }

        // Fallback to direct API
        String instrumentToken = resolveInstrumentToken(instrumentType);
        return fetchCandlesFromAPI(instrumentToken, "minute",
                Math.max(config.getVwapCandleCount(),
                        Math.max(config.getRangeCompressionCandles(),
                                Math.max(config.getOscillationCandleCount(),
                                        config.getVwapPullbackCandleCount()))));
    }

    /**
     * Fetch candles for ADX computation.
     * Prefers MDE cache for 1-min candles, falls back to API for other intervals.
     */
    private List<HistoricalData> fetchADXCandles(String instrumentType, String instrumentToken) {
        String interval = config.getAdxCandleInterval();

        // If using 1-min candles, try MDE cache first
        if ("minute".equals(interval)) {
            try {
                Optional<List<HistoricalData>> cached = marketDataEngine.getCandles(instrumentType);
                if (cached.isPresent() && cached.get().size() >= config.getAdxCandleCount()) {
                    return cached.get();
                }
            } catch (Exception e) {
                log.debug("MDE candle cache miss for ADX {}: {}", instrumentType, e.getMessage());
            }
        }

        return fetchCandlesFromAPI(instrumentToken, interval, config.getAdxCandleCount());
    }

    /**
     * Fetch historical candles from Kite API.
     */
    private List<HistoricalData> fetchCandlesFromAPI(String instrumentToken, String interval, int candleCount) {
        try {
            int minutesPerCandle = parseIntervalMinutes(interval);
            int totalMinutes = minutesPerCandle * (candleCount + 5);

            ZonedDateTime now = ZonedDateTime.now(IST);
            ZonedDateTime from = now.minusMinutes(totalMinutes);

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
            log.warn("fetchCandlesFromAPI failed: token={}, interval={}: {}", instrumentToken, interval, e.getMessage());
        }
        return Collections.emptyList();
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

    // ==================== INSTRUMENT RESOLUTION HELPERS ====================

    private String resolveInstrumentToken(String instrumentType) {
        String cachedToken = instrumentTokenCache.get(instrumentType.toUpperCase());
        if (cachedToken != null) {
            return cachedToken;
        }

        String key = instrumentType.toUpperCase();
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
            log.warn("Failed to resolve instrument token for {}: {}", instrumentType, e.getMessage());
        }

        // Hardcoded fallback
        String fallbackToken = switch (key) {
            case "NIFTY" -> "256265";
            case "BANKNIFTY" -> "260105";
            default -> throw new RuntimeException("Unknown instrument type: " + instrumentType);
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

    /**
     * Find the nearest expiry date from NFO instruments for the given underlying.
     */
    private Date findNearestExpiry(List<Instrument> instruments, String underlyingName) {
        long nowMs = System.currentTimeMillis();
        long todayEpochDay = (nowMs + IST_OFFSET_MS) / MS_PER_DAY;
        Date nearest = null;

        for (int i = 0; i < instruments.size(); i++) {
            Instrument inst = instruments.get(i);
            if (!underlyingName.equals(inst.name)) continue;
            if (inst.expiry == null) continue;
            if (!"CE".equals(inst.instrument_type) && !"PE".equals(inst.instrument_type)) continue;

            long expiryEpochDay = (inst.expiry.getTime() + IST_OFFSET_MS) / MS_PER_DAY;
            if (expiryEpochDay < todayEpochDay) continue; // Expired (before today)

            if (nearest == null || inst.expiry.before(nearest)) {
                nearest = inst.expiry;
            }
        }
        return nearest;
    }

    /**
     * Fast same-day check without Calendar allocation.
     * HFT: Zero allocation — O(1) comparison using epoch-day arithmetic.
     */
    private boolean isSameDay(Date d1, Date d2) {
        long day1 = (d1.getTime() + IST_OFFSET_MS) / MS_PER_DAY;
        long day2 = (d2.getTime() + IST_OFFSET_MS) / MS_PER_DAY;
        return day1 == day2;
    }
}

