package com.tradingbot.service.strategy;

import com.tradingbot.config.NeutralMarketConfig;
import com.tradingbot.service.InstrumentCacheService;
import com.tradingbot.service.MarketDataEngine;
import com.tradingbot.service.TradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.LTPQuote;
import com.zerodhatech.models.Quote;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;

/**
 * Neutral Market Detection Engine.
 *
 * Evaluates 5 independent market-neutrality signals and produces a composite score (0–10).
 * The market is considered neutral (range-bound) when the score meets a configurable minimum
 * threshold, indicating favorable conditions for ATM straddle entry.
 *
 * <h2>Signals (each contributes +2)</h2>
 * <ol>
 *   <li>VWAP Deviation — spot price near session VWAP</li>
 *   <li>ADX Trend Strength — ADX below 18 indicates no strong trend</li>
 *   <li>Gamma Pin Detection — spot price pinned near max OI strike</li>
 *   <li>Range Compression — last N candles confined to a tight range</li>
 *   <li>Dual Premium Decay — both ATM CE and PE premiums decaying</li>
 * </ol>
 *
 * <h2>Thread Safety</h2>
 * Uses {@link AtomicReference} for all cached state. No synchronized blocks on the hot path.
 * Signal evaluation is stateless except for premium decay (requires previous snapshot).
 *
 * <h2>API Call Budget</h2>
 * Per evaluation (cached for 30s):
 * <ul>
 *   <li>1x getHistoricalData (1-min candles for VWAP + Range Compression)</li>
 *   <li>1x getHistoricalData (3-min candles for ADX)</li>
 *   <li>1x getQuote batch (for OI — Gamma Pin)</li>
 *   <li>1x getLTP (ATM CE+PE for premium decay)</li>
 * </ul>
 * Total: 4 API calls per evaluation, well within Kite's 10 req/sec limit.
 *
 * @since 4.2
 * @see NeutralMarketConfig
 */
@Service
@Slf4j
public class NeutralMarketDetectorService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int SCORE_PER_SIGNAL = 2;
    private static final int TOTAL_SIGNALS = 5;
    private static final int MAX_SCORE = SCORE_PER_SIGNAL * TOTAL_SIGNALS;

    private final NeutralMarketConfig config;
    private final TradingService tradingService;
    private final InstrumentCacheService instrumentCacheService;
    private final MarketDataEngine marketDataEngine;

    /** Cached composite evaluation result. */
    private final AtomicReference<CachedResult> cachedResult = new AtomicReference<>();

    /** Previous premium snapshot for dual premium decay comparison. */
    private final AtomicReference<PremiumSnapshot> previousPremiumSnapshot = new AtomicReference<>();

    public NeutralMarketDetectorService(NeutralMarketConfig config,
                                        TradingService tradingService,
                                        InstrumentCacheService instrumentCacheService,
                                        MarketDataEngine marketDataEngine) {
        this.config = config;
        this.tradingService = tradingService;
        this.instrumentCacheService = instrumentCacheService;
        this.marketDataEngine = marketDataEngine;
    }

    // ==================== PUBLIC RECORDS ====================

    /**
     * Result of a single signal evaluation.
     */
    public record SignalResult(String name, int score, int maxScore, boolean passed, String detail) {
        static SignalResult passed(String name, String detail) {
            return new SignalResult(name, SCORE_PER_SIGNAL, SCORE_PER_SIGNAL, true, detail);
        }

        static SignalResult failed(String name, String detail) {
            return new SignalResult(name, 0, SCORE_PER_SIGNAL, false, detail);
        }

        static SignalResult unavailable(String name, String reason) {
            return new SignalResult(name, 0, SCORE_PER_SIGNAL, false, "DATA_UNAVAILABLE: " + reason);
        }
    }

    /**
     * Composite result of all signal evaluations.
     */
    public record NeutralMarketResult(
            boolean neutral,
            int totalScore,
            int maxScore,
            int minimumRequired,
            List<SignalResult> signals,
            String summary,
            Instant evaluatedAt
    ) {
        /** Factory for when data is entirely unavailable. */
        static NeutralMarketResult dataUnavailable(boolean allowTrade, String reason, int minimumRequired) {
            return new NeutralMarketResult(
                    allowTrade, 0, MAX_SCORE, minimumRequired,
                    Collections.emptyList(),
                    "Data unavailable: " + reason + ". Fail-safe: " + (allowTrade ? "ALLOW" : "BLOCK"),
                    Instant.now()
            );
        }

        /** Factory for disabled filter. */
        static NeutralMarketResult disabled() {
            return new NeutralMarketResult(
                    true, MAX_SCORE, MAX_SCORE, 0,
                    Collections.emptyList(),
                    "Neutral market filter disabled",
                    Instant.now()
            );
        }
    }

    // ==================== INTERNAL RECORDS ====================

    private record CachedResult(NeutralMarketResult result, Instant fetchTime, String instrumentType) {
        boolean isExpired(long ttlMs) {
            return Instant.now().toEpochMilli() - fetchTime.toEpochMilli() > ttlMs;
        }

        boolean isForInstrument(String instrumentType) {
            return this.instrumentType != null && this.instrumentType.equalsIgnoreCase(instrumentType);
        }
    }

    private record PremiumSnapshot(double ceLtp, double peLtp, Instant timestamp, String instrumentType, double atmStrike) {}

    // ==================== PUBLIC API ====================

    /**
     * Evaluate all neutral market signals for the given instrument.
     *
     * @param instrumentType "NIFTY" or "BANKNIFTY"
     * @return composite result with individual signal scores
     */
    public NeutralMarketResult evaluate(String instrumentType) {
        if (!config.isEnabled()) {
            log.debug("Neutral market filter is disabled, allowing trade");
            return NeutralMarketResult.disabled();
        }

        // Check cache — must match instrumentType
        CachedResult cached = cachedResult.get();
        if (cached != null && !cached.isExpired(config.getCacheTtlMs()) && cached.isForInstrument(instrumentType)) {
            log.debug("Returning cached neutral market result: score={}/{}",
                    cached.result().totalScore(), cached.result().maxScore());
            return cached.result();
        }

        // Evaluate fresh
        try {
            NeutralMarketResult result = evaluateAllSignals(instrumentType);
            cachedResult.set(new CachedResult(result, Instant.now(), instrumentType));
            return result;
        } catch (Exception e) {
            // Note: KiteException is caught individually in each signal method and in
            // evaluateAllSignals. This catch is a safety net for unexpected RuntimeExceptions.
            log.error("Neutral market evaluation failed: {}", e.getMessage(), e);
            boolean allow = config.isAllowOnDataUnavailable();
            NeutralMarketResult fallback = NeutralMarketResult.dataUnavailable(
                    allow, e.getMessage(), config.getMinimumScore());
            cachedResult.set(new CachedResult(fallback, Instant.now(), instrumentType));
            return fallback;
        }
    }

    /**
     * Convenience: check if the market is neutral for the given instrument.
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
        cachedResult.set(null);
        previousPremiumSnapshot.set(null);
        log.debug("Neutral market detector cache cleared");
    }

    // ==================== CORE EVALUATION ====================

    /** Market opens at 09:15 IST. Candle data is only available after this. */
    private static final java.time.LocalTime MARKET_OPEN = java.time.LocalTime.of(9, 15);
    /** Market closes at 15:30 IST. No new candles after this. */
    private static final java.time.LocalTime MARKET_CLOSE = java.time.LocalTime.of(15, 30);
    /** Minimum minutes after open before enough candles exist for ADX. With 1-min candles and period 7, only ~15 candles warmup needed. */
    private static final int MIN_MINUTES_AFTER_OPEN_FOR_ADX = 20;

    /**
     * Check if current IST time is within market trading hours.
     * Candle data is only meaningful between 09:15 and 15:30 IST on trading days.
     * Package-private for test override.
     */
    boolean isWithinMarketHours() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        java.time.LocalTime time = now.toLocalTime();
        java.time.DayOfWeek day = now.getDayOfWeek();

        // Skip weekends
        if (day == java.time.DayOfWeek.SATURDAY || day == java.time.DayOfWeek.SUNDAY) {
            return false;
        }

        return !time.isBefore(MARKET_OPEN) && !time.isAfter(MARKET_CLOSE);
    }

    /**
     * Check if enough time has elapsed since market open for ADX candles to be available.
     * Package-private for test override.
     */
    boolean hasEnoughTimeForADX() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        java.time.LocalTime time = now.toLocalTime();
        long minutesSinceOpen = java.time.Duration.between(MARKET_OPEN, time).toMinutes();
        return minutesSinceOpen >= MIN_MINUTES_AFTER_OPEN_FOR_ADX;
    }

    private NeutralMarketResult evaluateAllSignals(String instrumentType) {
        log.info("Evaluating neutral market signals for {}", instrumentType);
        long startTime = System.currentTimeMillis();

        // ==================== MARKET HOURS GUARD ====================
        if (!isWithinMarketHours()) {
            ZonedDateTime now = ZonedDateTime.now(IST);
            log.warn("Neutral market evaluation skipped: outside market hours. " +
                            "currentTime={}, marketOpen={}, marketClose={}, dayOfWeek={}",
                    now.toLocalTime(), MARKET_OPEN, MARKET_CLOSE, now.getDayOfWeek());
            boolean allow = config.isAllowOnDataUnavailable();
            return NeutralMarketResult.dataUnavailable(allow,
                    "Outside market hours (" + now.toLocalTime() + " IST, " + now.getDayOfWeek() + ")",
                    config.getMinimumScore());
        }

        double spotPrice = fetchSpotPrice(instrumentType);
        if (spotPrice <= 0) {
            log.warn("Could not fetch spot price for {}", instrumentType);
            boolean allow = config.isAllowOnDataUnavailable();
            return NeutralMarketResult.dataUnavailable(allow, "Spot price unavailable", config.getMinimumScore());
        }

        double strikeInterval = getStrikeInterval(instrumentType);
        String instrumentToken = resolveInstrumentToken(instrumentType);

        // Fetch 1-minute candles once — shared by VWAP and Range Compression
        List<HistoricalData> oneMinCandles = fetchOneMinuteCandles(instrumentType, instrumentToken);

        // HFT: Fetch NFO instruments once — shared by Gamma Pin and Premium Decay
        List<Instrument> nfoInstruments = Collections.emptyList();
        Date nearestExpiry = null;
        String underlyingName = getUnderlyingName(instrumentType);
        try {
            nfoInstruments = instrumentCacheService.getInstruments("NFO");
            nearestExpiry = findNearestExpiry(nfoInstruments, underlyingName);
        } catch (KiteException | IOException e) {
            log.warn("Failed to fetch NFO instruments: {}", e.getMessage());
        }

        // Evaluate all 5 signals
        List<SignalResult> signals = new ArrayList<>(TOTAL_SIGNALS);

        // Determine if expiry day for tighter range compression threshold
        boolean isExpiryDayForSignals = nearestExpiry != null && isSameDay(nearestExpiry, new Date());

        signals.add(calculateVWAPDeviation(spotPrice, oneMinCandles));
        signals.add(calculateADX(instrumentToken));
        signals.add(detectGammaPin(spotPrice, instrumentType, strikeInterval, nfoInstruments, nearestExpiry));
        signals.add(calculateRangeCompression(spotPrice, oneMinCandles, isExpiryDayForSignals));
        signals.add(detectDualPremiumDecay(spotPrice, instrumentType, strikeInterval, nfoInstruments, nearestExpiry));

        // Compute composite score
        int totalScore = 0;
        for (SignalResult s : signals) {
            totalScore += s.score();
        }

        // Determine effective minimum score — tighter on expiry day
        boolean isExpiryDay = nearestExpiry != null && isSameDay(nearestExpiry, new Date());
        int effectiveMinScore = isExpiryDay
                ? config.getExpiryDayMinimumScore()
                : config.getMinimumScore();
        boolean neutral = totalScore >= effectiveMinScore;

        if (isExpiryDay) {
            log.info("EXPIRY DAY detected — using tighter minimum score: {}/{} (normal={})",
                    effectiveMinScore, MAX_SCORE, config.getMinimumScore());
        }

        // Build per-signal summary string for record
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < signals.size(); i++) {
            SignalResult s = signals.get(i);
            if (i > 0) sb.append(' ');
            sb.append(s.passed() ? "✓" : "✗").append(' ').append(s.name())
              .append('(').append(s.score()).append('/').append(s.maxScore()).append(')');
        }
        String summary = sb.toString();

        long elapsed = System.currentTimeMillis() - startTime;

        // ==================== STRUCTURED DECISION LOGS ====================

        // 1. Neutral score summary
        log.info("Neutral market score calculated: instrument={}, score={}, maxScore={}, threshold={}, isNeutral={}",
                instrumentType, totalScore, MAX_SCORE, config.getMinimumScore(), neutral);

        // 2. Per-signal pass/fail report (single log line per signal for clarity)
        for (SignalResult s : signals) {
            log.info("Signal result: name={}, score={}/{}, passed={}, detail=[{}]",
                    s.name(), s.score(), s.maxScore(), s.passed(), s.detail());
        }

        // 3. Single aggregated decision log with all indicator details
        log.info("Neutral evaluation summary: instrument={}, price={}, signals=[{}], score={}/{}, isNeutral={}, elapsedMs={}",
                instrumentType, String.format("%.2f", spotPrice), summary,
                totalScore, MAX_SCORE, neutral, elapsed);

        return new NeutralMarketResult(neutral, totalScore, MAX_SCORE,
                effectiveMinScore, Collections.unmodifiableList(signals), summary, Instant.now());
    }

    // ==================== SIGNAL 1: VWAP DEVIATION ====================

    /**
     * Calculate percentage deviation between current price and VWAP.
     * VWAP = Σ(typicalPrice × volume) / Σ(volume), where typicalPrice = (H+L+C)/3.
     *
     * @param spotPrice current spot price
     * @param candles   1-minute candles (shared fetch)
     * @return SignalResult with score +2 if deviation &lt; threshold
     */
    private SignalResult calculateVWAPDeviation(double spotPrice, List<HistoricalData> candles) {
        final String signalName = "VWAP_DEVIATION";
        try {
            if (candles == null || candles.size() < 2) {
                return SignalResult.unavailable(signalName, "Insufficient candle data for VWAP");
            }

            // Use up to vwapCandleCount candles from the tail
            int count = Math.min(config.getVwapCandleCount(), candles.size());
            int startIdx = candles.size() - count;

            double sumTPxVol = 0.0;
            double sumVol = 0.0;
            double sumTP = 0.0;
            int candlesUsed = 0;

            for (int i = startIdx; i < candles.size(); i++) {
                HistoricalData candle = candles.get(i);
                double typicalPrice = (candle.high + candle.low + candle.close) / 3.0;
                double volume = candle.volume;
                sumTPxVol += typicalPrice * volume;
                sumVol += volume;
                sumTP += typicalPrice;
                candlesUsed++;
            }

            // NIFTY 50 is an index — Kite returns volume=0 for all index candles.
            // When volume is unavailable, fall back to SMA of typical price as VWAP proxy.
            double vwap;
            boolean volumeAvailable = sumVol > 0;
            if (volumeAvailable) {
                vwap = sumTPxVol / sumVol;
            } else if (candlesUsed > 0) {
                vwap = sumTP / candlesUsed;
                log.debug("VWAP: Using SMA of typical price as proxy (volume unavailable for index), candlesUsed={}", candlesUsed);
            } else {
                return SignalResult.unavailable(signalName, "No candle data for VWAP computation");
            }
            double deviation = Math.abs(spotPrice - vwap) / vwap;
            double deviationPct = deviation * 100;
            double thresholdPct = config.getVwapDeviationThreshold() * 100;
            boolean passed = deviation < config.getVwapDeviationThreshold();

            log.info("VWAP deviation check: price={}, vwap={}, deviationPct={}, threshold={}, passed={}",
                    String.format("%.2f", spotPrice), String.format("%.2f", vwap),
                    String.format("%.4f", deviationPct), String.format("%.4f", thresholdPct), passed);
            log.debug("VWAP detail: candlesUsed={}, volumeAvailable={}, sumVol={}, method={}",
                    candlesUsed, volumeAvailable, String.format("%.0f", sumVol),
                    volumeAvailable ? "VOLUME_WEIGHTED" : "SMA_PROXY");

            String detail = String.format("VWAP=%.2f, Spot=%.2f, Deviation=%.4f%% (threshold=%.4f%%)",
                    vwap, spotPrice, deviationPct, thresholdPct);
            return passed ? SignalResult.passed(signalName, detail) : SignalResult.failed(signalName, detail);

        } catch (Exception e) {
            log.warn("Signal {} evaluation failed: {}", signalName, e.getMessage());
            return SignalResult.unavailable(signalName, e.getMessage());
        }
    }

    // ==================== SIGNAL 2: ADX TREND STRENGTH ====================

    /**
     * Compute ADX using Wilder's smoothing on 3-minute candles (period=14).
     *
     * Why 3-minute candles: With period=14, Wilder's smoothing needs ~2×period warmup.
     * 50 three-minute candles span ~2.5 hours, yielding ~22 valid ADX readings.
     * This provides stable, accurate trend detection compared to noisy 1-minute ADX.
     *
     * @param instrumentToken NSE index instrument token
     * @return SignalResult with score +2 if ADX &lt; threshold
     */
    private SignalResult calculateADX(String instrumentToken) {
        final String signalName = "ADX_TREND";
        try {
            // Early-session guard: ADX needs ~90 minutes of 3-min candles after market open
            if (!hasEnoughTimeForADX()) {
                ZonedDateTime now = ZonedDateTime.now(IST);
                long minutesSinceOpen = java.time.Duration.between(MARKET_OPEN, now.toLocalTime()).toMinutes();
                log.info("ADX check: UNAVAILABLE — only {}min since market open, need {}min for reliable ADX. " +
                                "Returning unavailable (score 0) — insufficient data for trend assessment.",
                        minutesSinceOpen, MIN_MINUTES_AFTER_OPEN_FOR_ADX);
                return SignalResult.unavailable(signalName,
                        String.format("Early session: %dmin since open (need %dmin). Insufficient data for ADX.",
                                minutesSinceOpen, MIN_MINUTES_AFTER_OPEN_FOR_ADX));
            }

            List<HistoricalData> candles = fetchCandles(instrumentToken,
                    config.getAdxCandleInterval(), config.getAdxCandleCount());

            if (candles == null || candles.size() < config.getAdxPeriod() * 2 + 1) {
                return SignalResult.unavailable(signalName,
                        "Insufficient candles for ADX: need " + (config.getAdxPeriod() * 2 + 1)
                                + ", got " + (candles == null ? 0 : candles.size()));
            }

            double[] adxValues = computeADXSeries(candles, config.getAdxPeriod());
            if (adxValues == null || adxValues.length == 0) {
                return SignalResult.unavailable(signalName, "ADX computation returned no values");
            }

            double latestADX = adxValues[adxValues.length - 1];
            boolean belowThreshold = latestADX < config.getAdxThreshold();

            // Optional: check if ADX is falling for last 3 readings
            // "Falling" means adx[n-1] < adx[n-2] < adx[n-3] — consecutive decline
            boolean adxFalling = true;
            if (config.isAdxFallingCheckEnabled() && adxValues.length >= 3) {
                int n = adxValues.length;
                // Check: latest < previous AND previous < one-before-that
                if (adxValues[n - 1] >= adxValues[n - 2] || adxValues[n - 2] >= adxValues[n - 3]) {
                    adxFalling = false;
                }
            }

            boolean passed = config.isAdxFallingCheckEnabled()
                    ? belowThreshold && adxFalling
                    : belowThreshold;

            log.info("ADX check: adxValue={}, threshold={}, belowThreshold={}, fallingCheckEnabled={}, adxFalling={}, passed={}",
                    String.format("%.2f", latestADX), String.format("%.2f", config.getAdxThreshold()),
                    belowThreshold, config.isAdxFallingCheckEnabled(), adxFalling, passed);
            log.debug("ADX detail: candleInterval={}, candlesFetched={}, adxSeriesLength={}, period={}",
                    config.getAdxCandleInterval(), candles.size(), adxValues.length, config.getAdxPeriod());

            String detail = String.format("ADX=%.2f (threshold=%.2f), belowThreshold=%s, falling=%s",
                    latestADX, config.getAdxThreshold(), belowThreshold, adxFalling);
            return passed ? SignalResult.passed(signalName, detail) : SignalResult.failed(signalName, detail);

        } catch (Exception e) {
            log.warn("Signal {} evaluation failed: {}", signalName, e.getMessage());
            return SignalResult.unavailable(signalName, e.getMessage());
        }
    }

    /**
     * Compute ADX series using Wilder's smoothed method.
     *
     * <h3>Algorithm</h3>
     * <ol>
     *   <li>Compute True Range (TR), +DM, -DM for each candle</li>
     *   <li>Apply Wilder's exponential smoothing (α = 1/period) to TR, +DM, -DM</li>
     *   <li>Derive +DI = 100 × smoothed(+DM) / smoothed(TR)</li>
     *   <li>Derive -DI = 100 × smoothed(-DM) / smoothed(TR)</li>
     *   <li>DX = 100 × |+DI − −DI| / (+DI + −DI)</li>
     *   <li>ADX = Wilder's smoothed DX</li>
     * </ol>
     *
     * <p>Package-private for unit testing.</p>
     *
     * @param candles historical candles (high, low, close)
     * @param period  smoothing period (typically 14)
     * @return array of ADX values (may be shorter than input due to warmup)
     */
    static double[] computeADXSeries(List<HistoricalData> candles, int period) {
        int n = candles.size();
        if (n < period + 1) {
            return new double[0];
        }

        // Step 1: Raw TR, +DM, -DM (start from index 1 — need previous candle)
        int rawLen = n - 1;
        double[] tr = new double[rawLen];
        double[] plusDM = new double[rawLen];
        double[] minusDM = new double[rawLen];

        for (int i = 1; i < n; i++) {
            HistoricalData curr = candles.get(i);
            HistoricalData prev = candles.get(i - 1);

            double highLow = curr.high - curr.low;
            double highPrevClose = Math.abs(curr.high - prev.close);
            double lowPrevClose = Math.abs(curr.low - prev.close);
            tr[i - 1] = Math.max(highLow, Math.max(highPrevClose, lowPrevClose));

            double upMove = curr.high - prev.high;
            double downMove = prev.low - curr.low;

            plusDM[i - 1] = (upMove > downMove && upMove > 0) ? upMove : 0;
            minusDM[i - 1] = (downMove > upMove && downMove > 0) ? downMove : 0;
        }

        // Step 2: Wilder's smoothed TR, +DM, -DM
        // First value = sum of first 'period' raw values
        if (rawLen < period) {
            return new double[0];
        }

        double smoothedTR = 0, smoothedPlusDM = 0, smoothedMinusDM = 0;
        for (int i = 0; i < period; i++) {
            smoothedTR += tr[i];
            smoothedPlusDM += plusDM[i];
            smoothedMinusDM += minusDM[i];
        }

        // Collect DX values starting from the initial period point
        int dxCapacity = rawLen - period + 1;
        if (dxCapacity <= 0) {
            return new double[0];
        }
        double[] dx = new double[dxCapacity];

        // DX at the initial period point
        double plusDI = (smoothedTR > 0) ? 100.0 * smoothedPlusDM / smoothedTR : 0;
        double minusDI = (smoothedTR > 0) ? 100.0 * smoothedMinusDM / smoothedTR : 0;
        double diSum = plusDI + minusDI;
        dx[0] = (diSum > 0) ? 100.0 * Math.abs(plusDI - minusDI) / diSum : 0;

        // Subsequent values: smoothed = prev - (prev/period) + current
        for (int i = period; i < rawLen; i++) {
            smoothedTR = smoothedTR - (smoothedTR / period) + tr[i];
            smoothedPlusDM = smoothedPlusDM - (smoothedPlusDM / period) + plusDM[i];
            smoothedMinusDM = smoothedMinusDM - (smoothedMinusDM / period) + minusDM[i];

            plusDI = (smoothedTR > 0) ? 100.0 * smoothedPlusDM / smoothedTR : 0;
            minusDI = (smoothedTR > 0) ? 100.0 * smoothedMinusDM / smoothedTR : 0;
            diSum = plusDI + minusDI;
            dx[i - period + 1] = (diSum > 0) ? 100.0 * Math.abs(plusDI - minusDI) / diSum : 0;
        }

        // Step 3: ADX = Wilder's smoothed DX
        // Need at least 'period' DX values for the first ADX
        if (dx.length < period) {
            // Not enough DX values; return the last DX as a rough estimate
            return new double[]{dx[dx.length - 1]};
        }

        int adxLen = dx.length - period + 1;
        double[] adx = new double[adxLen];

        // First ADX = simple average of first 'period' DX values
        double adxSum = 0;
        for (int i = 0; i < period; i++) {
            adxSum += dx[i];
        }
        adx[0] = adxSum / period;

        // Subsequent ADX using Wilder's smoothing
        for (int i = period; i < dx.length; i++) {
            adx[i - period + 1] = (adx[i - period] * (period - 1) + dx[i]) / period;
        }

        return adx;
    }

    // ==================== SIGNAL 3: GAMMA PIN (MAX OI STRIKE) ====================

    /**
     * Detect if spot price is pinned near the strike with maximum combined OI (Call + Put).
     * Uses batch quote fetch for OI data to minimize API calls.
     *
     * @param spotPrice      current spot price
     * @param instrumentType "NIFTY" or "BANKNIFTY"
     * @param strikeInterval strike interval (50 for NIFTY, 100 for BANKNIFTY)
     * @return SignalResult with score +2 if spot is near max OI strike
     */
    private SignalResult detectGammaPin(double spotPrice, String instrumentType, double strikeInterval,
                                        List<Instrument> nfoInstruments, Date nearestExpiry) {
        final String signalName = "GAMMA_PIN";
        try {
            double atmStrike = Math.round(spotPrice / strikeInterval) * strikeInterval;
            int strikesAround = config.getStrikesAroundAtm();

            String underlyingName = getUnderlyingName(instrumentType);

            if (nfoInstruments == null || nfoInstruments.isEmpty()) {
                return SignalResult.unavailable(signalName, "NFO instruments not available");
            }
            if (nearestExpiry == null) {
                return SignalResult.unavailable(signalName, "Could not determine nearest expiry");
            }

            // Build set of strikes to check
            Set<Double> targetStrikes = new LinkedHashSet<>();
            for (int i = -strikesAround; i <= strikesAround; i++) {
                targetStrikes.add(atmStrike + i * strikeInterval);
            }

            // Map strike -> [CE symbol, PE symbol] from instruments matching expiry
            Map<Double, String[]> strikeSymbols = new LinkedHashMap<>();
            for (double strike : targetStrikes) {
                strikeSymbols.put(strike, new String[2]); // [0]=CE, [1]=PE
            }

            for (Instrument inst : nfoInstruments) {
                if (!underlyingName.equals(inst.name)) continue;
                if (inst.expiry == null || !isSameDay(inst.expiry, nearestExpiry)) continue;

                try {
                    double strike = Double.parseDouble(inst.strike);
                    String[] symbols = strikeSymbols.get(strike);
                    if (symbols != null) {
                        if ("CE".equals(inst.instrument_type)) {
                            symbols[0] = "NFO:" + inst.tradingsymbol;
                        } else if ("PE".equals(inst.instrument_type)) {
                            symbols[1] = "NFO:" + inst.tradingsymbol;
                        }
                    }
                } catch (NumberFormatException ignored) {
                    // Skip instruments with non-numeric strike
                }
            }

            // Collect all valid symbols for batch quote fetch
            List<String> allSymbols = new ArrayList<>();
            Map<String, Double> symbolToStrike = new HashMap<>();

            for (Map.Entry<Double, String[]> entry : strikeSymbols.entrySet()) {
                double strike = entry.getKey();
                String[] symbols = entry.getValue();
                if (symbols[0] != null) {
                    allSymbols.add(symbols[0]);
                    symbolToStrike.put(symbols[0], strike);
                }
                if (symbols[1] != null) {
                    allSymbols.add(symbols[1]);
                    symbolToStrike.put(symbols[1], strike);
                }
            }

            if (allSymbols.isEmpty()) {
                return SignalResult.unavailable(signalName, "No option symbols found for OI fetch");
            }

            // Batch fetch quotes (contains OI field)
            Map<String, Quote> quotes = tradingService.getQuote(allSymbols.toArray(new String[0]));
            if (quotes == null || quotes.isEmpty()) {
                return SignalResult.unavailable(signalName, "Quote fetch returned empty");
            }

            // Aggregate OI per strike: combinedOI = callOI + putOI
            // Also track total call and put OI separately for PCR computation
            // Kite SDK Quote.oi is double
            Map<Double, Double> combinedOI = new HashMap<>();
            double totalCallOI = 0;
            double totalPutOI = 0;
            for (Map.Entry<String, Quote> qEntry : quotes.entrySet()) {
                String symbol = qEntry.getKey();
                Quote quote = qEntry.getValue();
                Double strike = symbolToStrike.get(symbol);
                if (strike != null && quote != null) {
                    double oi = quote.oi;
                    combinedOI.merge(strike, oi, Double::sum);
                    // Determine CE vs PE by symbol suffix
                    if (symbol.endsWith("CE")) {
                        totalCallOI += oi;
                    } else if (symbol.endsWith("PE")) {
                        totalPutOI += oi;
                    }
                }
            }

            if (combinedOI.isEmpty()) {
                return SignalResult.unavailable(signalName, "No OI data available");
            }

            // Find strike with max combined OI
            double maxOIStrike = atmStrike;
            double maxOI = 0;
            for (Map.Entry<Double, Double> entry : combinedOI.entrySet()) {
                if (entry.getValue() > maxOI) {
                    maxOI = entry.getValue();
                    maxOIStrike = entry.getKey();
                }
            }

            double distance = Math.abs(spotPrice - maxOIStrike) / spotPrice;
            double distancePct = distance * 100;
            double thresholdPct = config.getGammaPinThreshold() * 100;
            boolean passed = distance < config.getGammaPinThreshold();

            // PCR = Put-Call Ratio (informational — valuable for log analysis)
            double pcr = (totalCallOI > 0) ? totalPutOI / totalCallOI : 0.0;

            log.info("Gamma pin check: price={}, maxOIStrike={}, maxOI={}, distancePct={}, threshold={}, pcr={}, passed={}",
                    String.format("%.2f", spotPrice), String.format("%.0f", maxOIStrike),
                    String.format("%.0f", maxOI), String.format("%.4f", distancePct),
                    String.format("%.4f", thresholdPct), String.format("%.2f", pcr), passed);
            log.debug("Gamma pin detail: strikesScanned={}, symbolsFetched={}, quotesReceived={}, combinedOIEntries={}, totalCallOI={}, totalPutOI={}",
                    targetStrikes.size(), allSymbols.size(), quotes.size(), combinedOI.size(),
                    String.format("%.0f", totalCallOI), String.format("%.0f", totalPutOI));

            String detail = String.format("MaxOI strike=%.0f (OI=%.0f), Spot=%.2f, Distance=%.4f%% (threshold=%.4f%%), PCR=%.2f",
                    maxOIStrike, maxOI, spotPrice, distancePct, thresholdPct, pcr);
            return passed ? SignalResult.passed(signalName, detail) : SignalResult.failed(signalName, detail);

        } catch (Exception | KiteException e) {
            log.warn("Signal {} evaluation failed: {}", signalName, e.getMessage());
            return SignalResult.unavailable(signalName, e.getMessage());
        }
    }

    // ==================== SIGNAL 4: RANGE COMPRESSION ====================

    /**
     * Check if the last N candles are compressed within a tight range.
     *
     * @param spotPrice    current spot price
     * @param candles      1-minute candles (shared fetch)
     * @param isExpiryDay  whether today is expiry day (uses tighter threshold)
     * @return SignalResult with score +2 if range &lt; threshold
     */
    private SignalResult calculateRangeCompression(double spotPrice, List<HistoricalData> candles, boolean isExpiryDay) {
        final String signalName = "RANGE_COMPRESSION";
        try {
            int required = config.getRangeCompressionCandles();
            if (candles == null || candles.size() < required) {
                return SignalResult.unavailable(signalName,
                        "Insufficient candles: need " + required + ", got " + (candles == null ? 0 : candles.size()));
            }

            // Use tighter range threshold on expiry day
            double effectiveThreshold = isExpiryDay
                    ? config.getExpiryDayRangeThreshold()
                    : config.getRangeCompressionThreshold();

            // Use the last N candles
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
            double rangePct = rangeFraction * 100;
            double thresholdPct = effectiveThreshold * 100;
            boolean passed = rangeFraction < effectiveThreshold;

            log.info("Range compression check: rangePoints={}, rangePct={}, threshold={}, highestHigh={}, lowestLow={}, candles={}, isExpiryDay={}, passed={}",
                    String.format("%.2f", range), String.format("%.4f", rangePct),
                    String.format("%.4f", thresholdPct), String.format("%.2f", highestHigh),
                    String.format("%.2f", lowestLow), required, isExpiryDay, passed);

            String detail = String.format("Range=%.2f (H=%.2f, L=%.2f), RangePct=%.4f%% (threshold=%.4f%%), Candles=%d",
                    range, highestHigh, lowestLow, rangePct, thresholdPct, required);
            return passed ? SignalResult.passed(signalName, detail) : SignalResult.failed(signalName, detail);

        } catch (Exception e) {
            log.warn("Signal {} evaluation failed: {}", signalName, e.getMessage());
            return SignalResult.unavailable(signalName, e.getMessage());
        }
    }

    // ==================== SIGNAL 5: DUAL PREMIUM DECAY ====================

    /**
     * Check if both ATM CE and PE premiums are decaying compared to previous snapshot.
     * First invocation stores a baseline and returns unavailable (no prior data to compare).
     *
     * <h3>Guards</h3>
     * <ul>
     *   <li>ATM strike shift: if ATM strike changed between snapshots, skip (comparing different instruments)</li>
     *   <li>Maximum snapshot age: discard stale snapshots older than {@code premiumMaxSnapshotAgeMs}</li>
     *   <li>Minimum decay: both CE and PE must decay by at least {@code premiumMinDecayPct}%</li>
     * </ul>
     *
     * @param spotPrice      current spot price
     * @param instrumentType "NIFTY" or "BANKNIFTY"
     * @param strikeInterval strike interval
     * @return SignalResult with score +2 if both premiums are meaningfully decreasing
     */
    private SignalResult detectDualPremiumDecay(double spotPrice, String instrumentType, double strikeInterval,
                                                List<Instrument> nfoInstruments, Date nearestExpiry) {
        final String signalName = "PREMIUM_DECAY";
        try {
            double atmStrike = Math.round(spotPrice / strikeInterval) * strikeInterval;
            String underlyingName = getUnderlyingName(instrumentType);

            if (nfoInstruments == null || nfoInstruments.isEmpty()) {
                return SignalResult.unavailable(signalName, "NFO instruments not available");
            }
            if (nearestExpiry == null) {
                return SignalResult.unavailable(signalName, "Could not determine nearest expiry");
            }

            String ceSymbol = null;
            String peSymbol = null;
            for (Instrument inst : nfoInstruments) {
                if (!underlyingName.equals(inst.name)) continue;
                if (inst.expiry == null || !isSameDay(inst.expiry, nearestExpiry)) continue;
                try {
                    double strike = Double.parseDouble(inst.strike);
                    if (Math.abs(strike - atmStrike) < 0.01) {
                        if ("CE".equals(inst.instrument_type)) ceSymbol = "NFO:" + inst.tradingsymbol;
                        if ("PE".equals(inst.instrument_type)) peSymbol = "NFO:" + inst.tradingsymbol;
                    }
                } catch (NumberFormatException ignored) {
                    // Skip instruments with non-numeric strike
                }
                if (ceSymbol != null && peSymbol != null) break;
            }

            if (ceSymbol == null || peSymbol == null) {
                return SignalResult.unavailable(signalName,
                        "ATM options not found for strike " + atmStrike);
            }

            // Fetch current LTP for ATM CE and PE
            Map<String, LTPQuote> ltpMap = tradingService.getLTP(new String[]{ceSymbol, peSymbol});
            if (ltpMap == null || !ltpMap.containsKey(ceSymbol) || !ltpMap.containsKey(peSymbol)) {
                return SignalResult.unavailable(signalName, "LTP fetch failed for ATM options");
            }

            double currentCE = ltpMap.get(ceSymbol).lastPrice;
            double currentPE = ltpMap.get(peSymbol).lastPrice;
            Instant now = Instant.now();

            // Compare with previous snapshot — must be same instrument
            PremiumSnapshot previous = previousPremiumSnapshot.get();

            // Always store current as the new snapshot for next comparison (with ATM strike)
            previousPremiumSnapshot.set(new PremiumSnapshot(currentCE, currentPE, now, instrumentType, atmStrike));

            // Guard 1: First invocation — no prior data to compare
            if (previous == null || !instrumentType.equalsIgnoreCase(previous.instrumentType())) {
                String detail = String.format("Baseline stored: CE=%.2f, PE=%.2f, ATM=%.0f (first reading, no comparison)",
                        currentCE, currentPE, atmStrike);
                log.info("Signal {}: UNAVAILABLE — {}", signalName, detail);
                return SignalResult.unavailable(signalName, detail);
            }

            // Guard 2: ATM strike shift — comparing different instruments would be meaningless
            if (Math.abs(previous.atmStrike() - atmStrike) > 0.01) {
                String detail = String.format("ATM strike shifted: %.0f -> %.0f. Skipping cross-strike comparison. " +
                                "CE=%.2f, PE=%.2f (new baseline stored)",
                        previous.atmStrike(), atmStrike, currentCE, currentPE);
                log.info("Signal {}: UNAVAILABLE — {}", signalName, detail);
                return SignalResult.unavailable(signalName, detail);
            }

            // Guard 3: Minimum snapshot interval
            long intervalMs = now.toEpochMilli() - previous.timestamp().toEpochMilli();
            if (intervalMs < config.getPremiumSnapshotMinIntervalMs()) {
                String detail = String.format("Snapshot interval %dms < minimum %dms. CE=%.2f->%.2f, PE=%.2f->%.2f",
                        intervalMs, config.getPremiumSnapshotMinIntervalMs(),
                        previous.ceLtp(), currentCE, previous.peLtp(), currentPE);
                log.info("Signal {}: SKIPPED — {}", signalName, detail);
                return SignalResult.failed(signalName, detail);
            }

            // Guard 4: Maximum snapshot age — discard stale snapshots
            if (intervalMs > config.getPremiumMaxSnapshotAgeMs()) {
                String detail = String.format("Snapshot too old: %dms > max %dms. Discarding stale comparison. " +
                                "CE=%.2f, PE=%.2f (new baseline stored)",
                        intervalMs, config.getPremiumMaxSnapshotAgeMs(), currentCE, currentPE);
                log.info("Signal {}: UNAVAILABLE — {}", signalName, detail);
                return SignalResult.unavailable(signalName, detail);
            }

            boolean ceDecaying = currentCE < previous.ceLtp();
            boolean peDecaying = currentPE < previous.peLtp();

            // Guard 5: Minimum decay magnitude — prevent trivially small decays from passing
            double ceDecayPct = (previous.ceLtp() > 0)
                    ? (previous.ceLtp() - currentCE) / previous.ceLtp() * 100.0 : 0.0;
            double peDecayPct = (previous.peLtp() > 0)
                    ? (previous.peLtp() - currentPE) / previous.peLtp() * 100.0 : 0.0;
            double minDecayPct = config.getPremiumMinDecayPct();
            boolean ceDecaySufficient = ceDecaying && ceDecayPct >= minDecayPct;
            boolean peDecaySufficient = peDecaying && peDecayPct >= minDecayPct;
            boolean passed = ceDecaySufficient && peDecaySufficient;

            log.info("Premium decay check: callPrev={}, callNow={}, callDecayPct={}, putPrev={}, putNow={}, putDecayPct={}, " +
                            "minDecayPct={}, intervalMs={}, atmStrike={}, passed={}",
                    String.format("%.2f", previous.ceLtp()), String.format("%.2f", currentCE),
                    String.format("%.4f", ceDecayPct),
                    String.format("%.2f", previous.peLtp()), String.format("%.2f", currentPE),
                    String.format("%.4f", peDecayPct),
                    String.format("%.4f", minDecayPct),
                    intervalMs, String.format("%.0f", atmStrike), passed);

            String detail = String.format("CE: %.2f->%.2f (%.4f%% %s), PE: %.2f->%.2f (%.4f%% %s), minDecay=%.2f%%, interval=%dms",
                    previous.ceLtp(), currentCE, ceDecayPct, ceDecaySufficient ? "SUFFICIENT" : "INSUFFICIENT",
                    previous.peLtp(), currentPE, peDecayPct, peDecaySufficient ? "SUFFICIENT" : "INSUFFICIENT",
                    minDecayPct, intervalMs);
            return passed ? SignalResult.passed(signalName, detail) : SignalResult.failed(signalName, detail);

        } catch (Exception | KiteException e) {
            log.warn("Signal {} evaluation failed: {}", signalName, e.getMessage());
            return SignalResult.unavailable(signalName, e.getMessage());
        }
    }

    // ==================== DATA FETCHING HELPERS ====================

    private double fetchSpotPrice(String instrumentType) {
        // Prefer MarketDataEngine cache (refreshed every 1s) — avoids redundant API calls
        try {
            Optional<Double> cachedPrice = marketDataEngine.getIndexPrice(instrumentType);
            if (cachedPrice.isPresent()) {
                log.debug("fetchSpotPrice: using MarketDataEngine cache for {} = {}", instrumentType, cachedPrice.get());
                return cachedPrice.get();
            }
        } catch (Exception e) {
            log.debug("fetchSpotPrice: MarketDataEngine cache miss for {}: {}", instrumentType, e.getMessage());
        }

        // Fallback to direct API call when MDE is disabled or cache is stale
        try {
            String symbol = resolveSpotSymbol(instrumentType);
            Map<String, LTPQuote> ltp = tradingService.getLTP(new String[]{symbol});
            if (ltp != null && ltp.containsKey(symbol)) {
                log.debug("fetchSpotPrice: using direct API call for {} = {}", instrumentType, ltp.get(symbol).lastPrice);
                return ltp.get(symbol).lastPrice;
            }
        } catch (KiteException | IOException e) {
            log.warn("Failed to fetch spot price for {}: {}", instrumentType, e.getMessage());
        }
        return -1;
    }

    /**
     * Fetch 1-minute candles for VWAP and Range Compression (shared single API call).
     * Prefers MarketDataEngine cache (refreshed every 60s) to avoid redundant API calls.
     */
    private List<HistoricalData> fetchOneMinuteCandles(String instrumentType, String instrumentToken) {
        int requiredCount = Math.max(config.getVwapCandleCount(), config.getRangeCompressionCandles());

        // Prefer MarketDataEngine cache (refreshed every 60s)
        try {
            Optional<List<HistoricalData>> cached = marketDataEngine.getCandles(instrumentType);
            if (cached.isPresent() && cached.get().size() >= requiredCount) {
                log.debug("fetchOneMinuteCandles: using MarketDataEngine cache for {}, candles={}", instrumentType, cached.get().size());
                return cached.get();
            }
        } catch (Exception e) {
            log.debug("fetchOneMinuteCandles: MarketDataEngine cache miss for {}: {}", instrumentType, e.getMessage());
        }

        // Fallback to direct API call
        log.debug("fetchOneMinuteCandles: falling back to direct API call for {}", instrumentType);
        return fetchCandles(instrumentToken, "minute", requiredCount);
    }

    /**
     * Fetch historical candles from Kite API.
     */
    private List<HistoricalData> fetchCandles(String instrumentToken, String interval, int candleCount) {
        try {
            int minutesPerCandle = parseIntervalMinutes(interval);
            int totalMinutes = minutesPerCandle * (candleCount + 5); // +5 buffer for partial candles

            ZonedDateTime now = ZonedDateTime.now(IST);
            ZonedDateTime from = now.minusMinutes(totalMinutes);

            // Clamp 'from' to market open of today — requesting candles before 09:15 returns nothing
            ZonedDateTime todayOpen = now.toLocalDate().atTime(MARKET_OPEN).atZone(IST);
            if (from.isBefore(todayOpen)) {
                from = todayOpen;
                log.debug("fetchCandles: clamped 'from' to market open {} (requested window exceeded today's session)", todayOpen);
            }

            Date fromDate = Date.from(from.toInstant());
            Date toDate = Date.from(now.toInstant());

            log.debug("fetchCandles: token={}, interval={}, from={}, to={}, requestedCount={}",
                    instrumentToken, interval, from.toLocalTime(), now.toLocalTime(), candleCount);

            HistoricalData data = tradingService.getHistoricalData(
                    fromDate, toDate, instrumentToken, interval, false, false);

            if (data != null && data.dataArrayList != null && !data.dataArrayList.isEmpty()) {
                log.debug("fetchCandles: received {} {} candles for token {}", data.dataArrayList.size(), interval, instrumentToken);
                return data.dataArrayList;
            } else {
                log.warn("fetchCandles: received EMPTY result for token={}, interval={}, from={}, to={}",
                        instrumentToken, interval, from.toLocalTime(), now.toLocalTime());
            }
        } catch (KiteException | IOException e) {
            log.warn("fetchCandles: API call failed for token={}, interval={}: {}", instrumentToken, interval, e.getMessage());
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

    /**
     * Resolve the NSE instrument token for the given index using InstrumentCacheService.
     * Searches the cached NSE instruments by tradingsymbol. Falls back to hardcoded tokens.
     */
    private String resolveInstrumentToken(String instrumentType) {
        String tradingSymbol = switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> "NIFTY 50";
            case "BANKNIFTY" -> "NIFTY BANK";
            default -> instrumentType;
        };

        try {
            List<Instrument> nseInstruments = instrumentCacheService.getInstruments("NSE");
            for (Instrument inst : nseInstruments) {
                if (tradingSymbol.equals(inst.tradingsymbol)) {
                    String token = String.valueOf(inst.instrument_token);
                    log.debug("Resolved instrument token for {}: {}", instrumentType, token);
                    return token;
                }
            }
        } catch (KiteException | IOException e) {
            log.warn("Failed to resolve instrument token from cache for {}: {}", instrumentType, e.getMessage());
        }

        // Hardcoded fallback (known stable tokens)
        log.warn("Falling back to hardcoded instrument token for {}", instrumentType);
        return switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> "256265";
            case "BANKNIFTY" -> "260105";
            default -> throw new RuntimeException("Unknown instrument type: " + instrumentType);
        };
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
     * Returns the closest future (or today) expiry.
     */
    private Date findNearestExpiry(List<Instrument> instruments, String underlyingName) {
        Date now = new Date();
        Date nearest = null;

        for (Instrument inst : instruments) {
            if (!underlyingName.equals(inst.name)) continue;
            if (inst.expiry == null) continue;
            if (!"CE".equals(inst.instrument_type) && !"PE".equals(inst.instrument_type)) continue;

            // Only consider future expiries (or today)
            if (inst.expiry.before(now) && !isSameDay(inst.expiry, now)) continue;

            if (nearest == null || inst.expiry.before(nearest)) {
                nearest = inst.expiry;
            }
        }
        return nearest;
    }

    /**
     * Fast same-day check without Calendar allocation.
     * Converts both dates to IST day-epoch for O(1) comparison.
     * HFT: Zero allocation — avoids 2× Calendar.getInstance() per call in hot loops.
     */
    private boolean isSameDay(Date d1, Date d2) {
        // IST offset = +5:30 = 19800000ms
        long IST_OFFSET_MS = 19800000L;
        long MS_PER_DAY = 86400000L;
        long day1 = (d1.getTime() + IST_OFFSET_MS) / MS_PER_DAY;
        long day2 = (d2.getTime() + IST_OFFSET_MS) / MS_PER_DAY;
        return day1 == day2;
    }
}
















