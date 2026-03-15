package com.tradingbot.service;

import com.tradingbot.config.MarketDataEngineConfig;
import com.tradingbot.service.session.UserSessionManager;
import com.tradingbot.util.CurrentUserContext;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.LTPQuote;
import com.zerodhatech.models.Quote;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static com.tradingbot.service.TradingConstants.*;

/**
 * MarketDataEngine — centralized, high-performance background service responsible
 * for all market data operations in the trading bot.
 *
 * <h2>Architecture</h2>
 * Runs as a background engine that keeps data warm in cache so strategy execution
 * (ATM straddle, short strangle, etc.) can read pre-computed values near-instantly.
 *
 * <h2>Responsibilities</h2>
 * <ol>
 *   <li>{@link #getIndexPrice(String)} — Cached spot price of NIFTY/BANKNIFTY/SENSEX</li>
 *   <li>{@link #getOptionChain(String, String)} — Pre-fetched option chain for index/expiry</li>
 *   <li>{@link #getCandles(String)} — Cached OHLCV candle data for a symbol</li>
 *   <li>{@link #getVWAP(String)} — Pre-computed VWAP from candle data</li>
 *   <li>{@link #getPrecomputedATMStrike(String)} — Pre-computed ATM strike by delta</li>
 *   <li>{@link #getPrecomputedDelta(String, double)} — Pre-computed delta for a strike</li>
 *   <li>{@link #getPrecomputedStrikeByDelta(String, double, String)} — Pre-computed strike for target delta</li>
 * </ol>
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>All expensive operations are pre-computed on scheduled refresh cycles (1–60s)</li>
 *   <li>Strategy execution only READs from cache — never triggers a live fetch inline</li>
 *   <li>Thread-safe via ConcurrentHashMap + AtomicReference + volatile cache entries</li>
 *   <li>Staleness detection: each cache entry carries a timestamp; consumers check TTL</li>
 *   <li>Graceful degradation: if engine is disabled or data stale, strategies fall back to legacy</li>
 * </ul>
 *
 * <h2>Concurrency & Staleness Risks</h2>
 * <ul>
 *   <li>Spot price: 1s refresh, 2s TTL — max 2s stale. Acceptable for ATM strike rounding.</li>
 *   <li>Delta: 5s refresh, 10s TTL — max 10s stale. Delta moves slowly near ATM.</li>
 *   <li>Option chain: 60s refresh, 120s TTL — instruments are static intraday.</li>
 *   <li>VWAP: 5s refresh, 10s TTL — VWAP changes slowly during session.</li>
 *   <li>Thread pool isolation: engine threads are separate from strategy execution threads.</li>
 *   <li>No locks on the read path: all reads are non-blocking ConcurrentHashMap.get().</li>
 * </ul>
 *
 * @since 4.2
 */
@Service
@Slf4j
public class MarketDataEngine {

    private final MarketDataEngineConfig config;
    private final TradingService tradingService;
    private final InstrumentCacheService instrumentCacheService;
    private final UserSessionManager userSessionManager;

    // ==================== CACHE STORES ====================

    /** Spot prices: key = instrumentType (e.g., "NIFTY"), value = CacheEntry<Double> */
    private final ConcurrentHashMap<String, CacheEntry<Double>> spotPriceCache = new ConcurrentHashMap<>();

    /** Option chains: key = "NIFTY_WEEKLY" or "NIFTY_2025-03-20", value = CacheEntry<List<Instrument>> */
    private final ConcurrentHashMap<String, CacheEntry<List<Instrument>>> optionChainCache = new ConcurrentHashMap<>();

    /** Pre-computed ATM strikes: key = instrumentType, value = CacheEntry<Double> */
    private final ConcurrentHashMap<String, CacheEntry<Double>> atmStrikeCache = new ConcurrentHashMap<>();

    /** Per-strike deltas: key = "NIFTY", value = CacheEntry<Map<Double, Double>> (strike → delta) */
    private final ConcurrentHashMap<String, CacheEntry<Map<Double, Double>>> deltaMapCache = new ConcurrentHashMap<>();

    /** Pre-computed strike-by-delta: key = "NIFTY_0.1_CE", value = CacheEntry<Double> */
    private final ConcurrentHashMap<String, CacheEntry<Double>> strikeByDeltaCache = new ConcurrentHashMap<>();

    /** VWAP values: key = instrumentType, value = CacheEntry<BigDecimal> */
    private final ConcurrentHashMap<String, CacheEntry<BigDecimal>> vwapCache = new ConcurrentHashMap<>();

    /** Candle data: key = "NSE:NIFTY 50_minute", value = CacheEntry<List<HistoricalData>> */
    private final ConcurrentHashMap<String, CacheEntry<List<HistoricalData>>> candleCache = new ConcurrentHashMap<>();

    /** Nearest weekly expiry per instrument: key = instrumentType, value = CacheEntry<Date> */
    private final ConcurrentHashMap<String, CacheEntry<Date>> nearestExpiryCache = new ConcurrentHashMap<>();

    // ==================== THREAD POOL ====================

    private ScheduledExecutorService scheduler;

    // ==================== METRICS ====================

    private final AtomicLong spotPriceRefreshCount = new AtomicLong(0);
    private final AtomicLong deltaRefreshCount = new AtomicLong(0);
    private final AtomicLong optionChainRefreshCount = new AtomicLong(0);
    private final AtomicLong vwapRefreshCount = new AtomicLong(0);
    private final AtomicLong cacheHitCount = new AtomicLong(0);
    private final AtomicLong cacheMissCount = new AtomicLong(0);

    // ==================== CONSTANTS ====================

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final double RISK_FREE_RATE = 0.065;

    public MarketDataEngine(MarketDataEngineConfig config,
                            TradingService tradingService,
                            InstrumentCacheService instrumentCacheService,
                            UserSessionManager userSessionManager) {
        this.config = config;
        this.tradingService = tradingService;
        this.instrumentCacheService = instrumentCacheService;
        this.userSessionManager = userSessionManager;
    }

    // ==================== LIFECYCLE ====================

    @PostConstruct
    public void start() {
        if (!config.isEnabled()) {
            log.info("MarketDataEngine is DISABLED. Strategies will use legacy inline API calls.");
            return;
        }

        log.info("MarketDataEngine starting with config: spotRefresh={}ms, deltaRefresh={}ms, " +
                        "optionChainRefresh={}ms, vwapRefresh={}ms, candleRefresh={}ms, threads={}",
                config.getSpotPriceRefreshMs(), config.getDeltaRefreshMs(),
                config.getOptionChainRefreshMs(), config.getVwapRefreshMs(),
                config.getCandleRefreshMs(), config.getThreadPoolSize());

        scheduler = Executors.newScheduledThreadPool(config.getThreadPoolSize(), r -> {
            Thread t = new Thread(r, "mkt-data-engine");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY + 1);
            return t;
        });

        // Schedule refresh tasks with staggered initial delays to avoid API burst
        scheduler.scheduleAtFixedRate(this::refreshSpotPrices,
                500, config.getSpotPriceRefreshMs(), TimeUnit.MILLISECONDS);

        scheduler.scheduleAtFixedRate(this::refreshOptionChains,
                2000, config.getOptionChainRefreshMs(), TimeUnit.MILLISECONDS);

        scheduler.scheduleAtFixedRate(this::refreshDeltas,
                3000, config.getDeltaRefreshMs(), TimeUnit.MILLISECONDS);

        scheduler.scheduleAtFixedRate(this::refreshVWAP,
                4000, config.getVwapRefreshMs(), TimeUnit.MILLISECONDS);

        scheduler.scheduleAtFixedRate(this::refreshCandles,
                5000, config.getCandleRefreshMs(), TimeUnit.MILLISECONDS);

        log.info("MarketDataEngine started successfully. Supported instruments: {}",
                config.getSupportedInstruments());
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            log.info("MarketDataEngine shutting down...");
            scheduler.shutdownNow();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("MarketDataEngine did not terminate within 5 seconds");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.info("MarketDataEngine stopped.");
        }
    }

    // ==================== PUBLIC READ API (zero latency, cache-only) ====================

    /**
     * Get cached spot price for an index instrument.
     * Returns Optional.empty() if data is stale or unavailable.
     *
     * @param instrumentType "NIFTY", "BANKNIFTY", or "SENSEX"
     * @return Cached spot price or empty
     */
    public Optional<Double> getIndexPrice(String instrumentType) {
        CacheEntry<Double> entry = spotPriceCache.get(instrumentType.toUpperCase());
        if (entry != null && !entry.isExpired(config.getSpotPriceTtlMs())) {
            cacheHitCount.incrementAndGet();
            return Optional.of(entry.value);
        }
        cacheMissCount.incrementAndGet();
        log.debug("Spot price cache MISS for {}", instrumentType);
        return Optional.empty();
    }

    /**
     * Get cached option chain for an instrument and expiry.
     *
     * @param instrumentType "NIFTY" or "BANKNIFTY"
     * @param expiry "WEEKLY", "MONTHLY", or "yyyy-MM-dd"
     * @return Cached option chain or empty list
     */
    public Optional<List<Instrument>> getOptionChain(String instrumentType, String expiry) {
        String key = instrumentType.toUpperCase() + "_" + expiry.toUpperCase();
        CacheEntry<List<Instrument>> entry = optionChainCache.get(key);
        if (entry != null && !entry.isExpired(config.getOptionChainTtlMs())) {
            cacheHitCount.incrementAndGet();
            return Optional.of(entry.value);
        }
        cacheMissCount.incrementAndGet();
        log.debug("Option chain cache MISS for {}", key);
        return Optional.empty();
    }

    /**
     * Get pre-computed ATM strike for an instrument (nearest delta to 0.5).
     *
     * @param instrumentType "NIFTY" or "BANKNIFTY"
     * @return Cached ATM strike or empty
     */
    public Optional<Double> getPrecomputedATMStrike(String instrumentType) {
        CacheEntry<Double> entry = atmStrikeCache.get(instrumentType.toUpperCase());
        if (entry != null && !entry.isExpired(config.getDeltaTtlMs())) {
            cacheHitCount.incrementAndGet();
            return Optional.of(entry.value);
        }
        cacheMissCount.incrementAndGet();
        log.debug("ATM strike cache MISS for {}", instrumentType);
        return Optional.empty();
    }

    /**
     * Get pre-computed delta value for a specific strike.
     *
     * @param instrumentType "NIFTY" or "BANKNIFTY"
     * @param strike Strike price
     * @return Cached delta or empty
     */
    public Optional<Double> getPrecomputedDelta(String instrumentType, double strike) {
        CacheEntry<Map<Double, Double>> entry = deltaMapCache.get(instrumentType.toUpperCase());
        if (entry != null && !entry.isExpired(config.getDeltaTtlMs())) {
            Double delta = entry.value.get(strike);
            if (delta != null) {
                cacheHitCount.incrementAndGet();
                return Optional.of(delta);
            }
        }
        cacheMissCount.incrementAndGet();
        return Optional.empty();
    }

    /**
     * Get pre-computed strike for a target delta and option type.
     *
     * @param instrumentType "NIFTY" or "BANKNIFTY"
     * @param targetDelta Target absolute delta (e.g., 0.1, 0.4)
     * @param optionType "CE" or "PE"
     * @return Cached strike or empty
     */
    public Optional<Double> getPrecomputedStrikeByDelta(String instrumentType, double targetDelta, String optionType) {
        String key = buildStrikeByDeltaKey(instrumentType, targetDelta, optionType);
        CacheEntry<Double> entry = strikeByDeltaCache.get(key);
        if (entry != null && !entry.isExpired(config.getDeltaTtlMs())) {
            cacheHitCount.incrementAndGet();
            return Optional.of(entry.value);
        }
        cacheMissCount.incrementAndGet();
        log.debug("StrikeByDelta cache MISS for {}", key);
        return Optional.empty();
    }

    /**
     * Get cached VWAP for an instrument.
     *
     * @param instrumentType "NIFTY" or "BANKNIFTY"
     * @return Cached VWAP or empty
     */
    public Optional<BigDecimal> getVWAP(String instrumentType) {
        CacheEntry<BigDecimal> entry = vwapCache.get(instrumentType.toUpperCase());
        if (entry != null && !entry.isExpired(config.getVwapTtlMs())) {
            cacheHitCount.incrementAndGet();
            return Optional.of(entry.value);
        }
        cacheMissCount.incrementAndGet();
        return Optional.empty();
    }

    /**
     * Get cached candle data for a symbol.
     *
     * @param instrumentType "NIFTY" or "BANKNIFTY"
     * @return Cached candle data or empty list
     */
    public Optional<List<HistoricalData>> getCandles(String instrumentType) {
        String key = instrumentType.toUpperCase() + "_minute";
        CacheEntry<List<HistoricalData>> entry = candleCache.get(key);
        if (entry != null && !entry.isExpired(config.getCandleTtlMs())) {
            cacheHitCount.incrementAndGet();
            return Optional.of(entry.value);
        }
        cacheMissCount.incrementAndGet();
        return Optional.empty();
    }

    /**
     * Get cached nearest weekly expiry date for an instrument.
     *
     * @param instrumentType "NIFTY" or "BANKNIFTY"
     * @return Cached expiry date or empty
     */
    public Optional<Date> getNearestWeeklyExpiry(String instrumentType) {
        CacheEntry<Date> entry = nearestExpiryCache.get(instrumentType.toUpperCase());
        if (entry != null && !entry.isExpired(config.getOptionChainTtlMs())) {
            return Optional.of(entry.value);
        }
        return Optional.empty();
    }

    /**
     * Check if the engine is enabled and has warm cache data.
     *
     * @return true if engine is running and has at least spot prices cached
     */
    public boolean isWarmedUp() {
        if (!config.isEnabled()) return false;
        for (String inst : config.getSupportedInstrumentsArray()) {
            if (!spotPriceCache.containsKey(inst.trim().toUpperCase())) return false;
        }
        return true;
    }

    /**
     * Get comprehensive cache statistics for monitoring/debugging.
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("enabled", config.isEnabled());
        stats.put("warmedUp", isWarmedUp());
        stats.put("spotPriceRefreshCount", spotPriceRefreshCount.get());
        stats.put("deltaRefreshCount", deltaRefreshCount.get());
        stats.put("optionChainRefreshCount", optionChainRefreshCount.get());
        stats.put("vwapRefreshCount", vwapRefreshCount.get());
        stats.put("totalCacheHits", cacheHitCount.get());
        stats.put("totalCacheMisses", cacheMissCount.get());

        // Per-instrument details
        for (String inst : config.getSupportedInstrumentsArray()) {
            String key = inst.trim().toUpperCase();
            Map<String, Object> instStats = new LinkedHashMap<>();

            CacheEntry<Double> spot = spotPriceCache.get(key);
            instStats.put("spotPrice", spot != null ? spot.value : null);
            instStats.put("spotPriceAgeMs", spot != null ? spot.ageMs() : null);
            instStats.put("spotPriceStale", spot == null || spot.isExpired(config.getSpotPriceTtlMs()));

            CacheEntry<Double> atm = atmStrikeCache.get(key);
            instStats.put("atmStrike", atm != null ? atm.value : null);
            instStats.put("atmStrikeAgeMs", atm != null ? atm.ageMs() : null);

            CacheEntry<Map<Double, Double>> deltas = deltaMapCache.get(key);
            instStats.put("deltaStrikeCount", deltas != null ? deltas.value.size() : 0);

            CacheEntry<BigDecimal> vwap = vwapCache.get(key);
            instStats.put("vwap", vwap != null ? vwap.value : null);

            String chainKey = key + "_WEEKLY";
            CacheEntry<List<Instrument>> chain = optionChainCache.get(chainKey);
            instStats.put("optionChainSize", chain != null ? chain.value.size() : 0);

            stats.put(key, instStats);
        }

        return stats;
    }

    // ==================== BACKGROUND REFRESH TASKS ====================

    /**
     * Refresh spot prices for all supported instruments.
     * Frequency: every 1 second (configurable).
     * API calls: 1 batch LTP call for all instruments.
     */
    private void refreshSpotPrices() {
        if (!isMarketHours()) return;

        String userId = getActiveUserId();
        if (userId == null) return;

        try {
            CurrentUserContext.runWithUserContext(userId, () -> {
                try {
                    String[] symbols = buildIndexSymbols();
                    Map<String, LTPQuote> ltpMap = tradingService.getLTP(symbols);

                    for (String instrument : config.getSupportedInstrumentsArray()) {
                        String symbol = mapInstrumentToSymbol(instrument.trim());
                        LTPQuote ltp = ltpMap.get(symbol);
                        if (ltp != null && ltp.lastPrice > 0) {
                            spotPriceCache.put(instrument.trim().toUpperCase(),
                                    new CacheEntry<>(ltp.lastPrice));
                        }
                    }

                    spotPriceRefreshCount.incrementAndGet();
                    log.trace("Spot prices refreshed: {}", spotPriceCache.keySet());
                } catch (KiteException | IOException e) {
                    log.warn("Failed to refresh spot prices: {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("Error in spot price refresh: {}", e.getMessage());
        }
    }

    /**
     * Refresh option chains for all supported instruments.
     * Frequency: every 60 seconds (configurable).
     * API calls: 1 getInstruments("NFO") call (cached by InstrumentCacheService).
     */
    private void refreshOptionChains() {
        if (!isMarketHours()) return;

        String userId = getActiveUserId();
        if (userId == null) return;

        try {
            CurrentUserContext.runWithUserContext(userId, () -> {
                try {
                    List<Instrument> allNfo = instrumentCacheService.getInstruments(EXCHANGE_NFO);

                    for (String instrument : config.getSupportedInstrumentsArray()) {
                        String instType = instrument.trim().toUpperCase();
                        String underlyingName = mapToUnderlyingName(instType);

                        // Filter weekly expiry instruments
                        List<Instrument> weeklyChain = new ArrayList<>();
                        Date nearestExpiry = null;

                        for (Instrument inst : allNfo) {
                            if (!underlyingName.equals(inst.name)) continue;
                            String optType = inst.instrument_type;
                            if (!OPTION_TYPE_CE.equals(optType) && !OPTION_TYPE_PE.equals(optType)) continue;
                            if (inst.expiry != null && isNearestWeeklyExpiry(inst.expiry)) {
                                weeklyChain.add(inst);
                                if (nearestExpiry == null) {
                                    nearestExpiry = inst.expiry;
                                }
                            }
                        }

                        if (!weeklyChain.isEmpty()) {
                            optionChainCache.put(instType + "_WEEKLY",
                                    new CacheEntry<>(Collections.unmodifiableList(weeklyChain)));
                            if (nearestExpiry != null) {
                                nearestExpiryCache.put(instType, new CacheEntry<>(nearestExpiry));
                            }
                        }
                    }

                    optionChainRefreshCount.incrementAndGet();
                    log.debug("Option chains refreshed for {}", config.getSupportedInstruments());
                } catch (KiteException | IOException e) {
                    log.warn("Failed to refresh option chains: {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("Error in option chain refresh: {}", e.getMessage());
        }
    }

    /**
     * Refresh delta calculations for all supported instruments.
     * Pre-computes: ATM strike, per-strike delta map, and strike-by-delta for common targets.
     * Frequency: every 5 seconds (configurable).
     * API calls: 1 batch quote call per instrument (via DeltaCacheService).
     */
    private void refreshDeltas() {
        if (!isMarketHours()) return;

        String userId = getActiveUserId();
        if (userId == null) return;

        for (String instrument : config.getSupportedInstrumentsArray()) {
            String instType = instrument.trim().toUpperCase();

            try {
                CurrentUserContext.runWithUserContext(userId, () -> {
                    try {
                        // Get spot price from our own cache (avoid API call)
                        CacheEntry<Double> spotEntry = spotPriceCache.get(instType);
                        if (spotEntry == null || spotEntry.isExpired(config.getSpotPriceTtlMs() * 5)) {
                            log.debug("Skipping delta refresh for {} — no spot price available", instType);
                            return;
                        }
                        double spotPrice = spotEntry.value;

                        // Get nearest expiry from our option chain cache
                        CacheEntry<Date> expiryEntry = nearestExpiryCache.get(instType);
                        if (expiryEntry == null) {
                            log.debug("Skipping delta refresh for {} — no expiry available", instType);
                            return;
                        }
                        Date expiry = expiryEntry.value;

                        // Get option chain from our cache
                        String chainKey = instType + "_WEEKLY";
                        CacheEntry<List<Instrument>> chainEntry = optionChainCache.get(chainKey);
                        if (chainEntry == null || chainEntry.value.isEmpty()) {
                            log.debug("Skipping delta refresh for {} — no option chain available", instType);
                            return;
                        }

                        double strikeInterval = getStrikeInterval(instType);
                        double approximateATM = Math.round(spotPrice / strikeInterval) * strikeInterval;

                        // Generate strikes to check (±10 around ATM)
                        Set<Double> strikesToCheck = new LinkedHashSet<>();
                        for (int i = -10; i <= 10; i++) {
                            strikesToCheck.add(approximateATM + i * strikeInterval);
                        }

                        // Build batch quote identifiers from cached option chain
                        List<String> identifiers = new ArrayList<>();
                        for (Instrument inst : chainEntry.value) {
                            String optType = inst.instrument_type;
                            if (!OPTION_TYPE_CE.equals(optType) && !OPTION_TYPE_PE.equals(optType)) continue;
                            try {
                                double instStrike = Double.parseDouble(inst.strike);
                                if (strikesToCheck.contains(instStrike)) {
                                    identifiers.add(inst.exchange + ":" + inst.tradingsymbol);
                                }
                            } catch (NumberFormatException ignored) {}
                        }

                        if (identifiers.isEmpty()) {
                            log.debug("No quote identifiers for {} delta refresh", instType);
                            return;
                        }

                        // Batch fetch quotes (1 API call for ~42 instruments)
                        Map<String, Quote> quotes = tradingService.getQuote(
                                identifiers.toArray(new String[0]));

                        // Calculate time to expiry
                        double timeToExpiry = calculateTimeToExpiry(expiry);
                        if (timeToExpiry <= 0) {
                            log.debug("Expiry passed for {}, skipping delta refresh", instType);
                            return;
                        }

                        // Extract mid prices
                        Map<Double, double[]> midPrices = extractMidPricesFromQuotes(
                                quotes, instType, strikesToCheck);

                        if (midPrices.isEmpty()) {
                            log.debug("No mid prices extracted for {} delta refresh", instType);
                            return;
                        }

                        // Calculate implied forward price
                        double forwardPrice = calculateImpliedForward(spotPrice, midPrices, timeToExpiry);

                        // Compute all deltas
                        Map<Double, Double> deltaMap = new HashMap<>();
                        double sqrtT = Math.sqrt(timeToExpiry);

                        for (Map.Entry<Double, double[]> e : midPrices.entrySet()) {
                            double strike = e.getKey();
                            double[] prices = e.getValue(); // [callMid, putMid]
                            if (prices[0] <= 0) continue;

                            double iv = solveIV(prices[0], forwardPrice, strike, timeToExpiry);
                            if (Double.isNaN(iv) || iv <= 1e-4 || iv > 3.0) continue;

                            double d1 = (Math.log(forwardPrice / strike) + 0.5 * iv * iv * timeToExpiry) / (iv * sqrtT);
                            double delta = cumulativeNormalDistribution(d1);
                            deltaMap.put(strike, delta);
                        }

                        if (deltaMap.isEmpty()) {
                            log.debug("Delta computation produced no results for {}", instType);
                            return;
                        }

                        // Store delta map
                        deltaMapCache.put(instType, new CacheEntry<>(Collections.unmodifiableMap(new HashMap<>(deltaMap))));

                        // Find ATM strike (closest to 0.5 delta)
                        double bestStrike = approximateATM;
                        double minDiff = Double.MAX_VALUE;
                        for (Map.Entry<Double, Double> e : deltaMap.entrySet()) {
                            double diff = Math.abs(e.getValue() - 0.5);
                            if (diff < minDiff) {
                                minDiff = diff;
                                bestStrike = e.getKey();
                            }
                        }
                        atmStrikeCache.put(instType, new CacheEntry<>(bestStrike));

                        // Pre-compute strikes for common target deltas (0.1, 0.15, 0.2, 0.3, 0.4, 0.5)
                        double[] targetDeltas = {0.1, 0.15, 0.2, 0.3, 0.4, 0.5};
                        for (double targetDelta : targetDeltas) {
                            // CE: find strike where callDelta ≈ targetDelta
                            double bestCE = findStrikeForDelta(deltaMap, targetDelta, true, approximateATM);
                            strikeByDeltaCache.put(buildStrikeByDeltaKey(instType, targetDelta, OPTION_TYPE_CE),
                                    new CacheEntry<>(bestCE));

                            // PE: find strike where |putDelta| ≈ targetDelta (i.e., 1 - callDelta ≈ targetDelta)
                            double bestPE = findStrikeForDelta(deltaMap, targetDelta, false, approximateATM);
                            strikeByDeltaCache.put(buildStrikeByDeltaKey(instType, targetDelta, OPTION_TYPE_PE),
                                    new CacheEntry<>(bestPE));
                        }

                        deltaRefreshCount.incrementAndGet();
                        log.debug("Delta refresh complete for {}: ATM={}, deltaStrikes={}", instType, bestStrike, deltaMap.size());

                    } catch (KiteException | IOException e) {
                        log.warn("Failed to refresh deltas for {}: {}", instType, e.getMessage());
                    }
                });
            } catch (Exception e) {
                log.warn("Error in delta refresh for {}: {}", instType, e.getMessage());
            }
        }
    }

    /**
     * Refresh VWAP for all supported instruments.
     * Frequency: every 5 seconds (configurable).
     * API calls: 1 getHistoricalData per instrument (uses existing session candles when possible).
     */
    private void refreshVWAP() {
        if (!isMarketHours()) return;

        String userId = getActiveUserId();
        if (userId == null) return;

        for (String instrument : config.getSupportedInstrumentsArray()) {
            String instType = instrument.trim().toUpperCase();
            try {
                CurrentUserContext.runWithUserContext(userId, () -> {
                    try {
                        // Use candle data to compute VWAP
                        String candleKey = instType + "_minute";
                        CacheEntry<List<HistoricalData>> candleEntry = candleCache.get(candleKey);
                        if (candleEntry == null || candleEntry.value.isEmpty()) {
                            log.trace("No candle data for VWAP calculation of {}", instType);
                            return;
                        }

                        BigDecimal vwap = computeVWAPFromCandles(candleEntry.value);
                        if (vwap != null && vwap.compareTo(BigDecimal.ZERO) > 0) {
                            vwapCache.put(instType, new CacheEntry<>(vwap));
                        }

                        vwapRefreshCount.incrementAndGet();
                        log.trace("VWAP refreshed for {}: {}", instType, vwap);
                    } catch (Exception e) {
                        log.warn("Failed to refresh VWAP for {}: {}", instType, e.getMessage());
                    }
                });
            } catch (Exception e) {
                log.warn("Error in VWAP refresh for {}: {}", instType, e.getMessage());
            }
        }
    }

    /**
     * Refresh candle data for all supported instruments.
     * Frequency: every 60 seconds (configurable).
     * API calls: 1 getHistoricalData per instrument.
     */
    private void refreshCandles() {
        if (!isMarketHours()) return;

        String userId = getActiveUserId();
        if (userId == null) return;

        for (String instrument : config.getSupportedInstrumentsArray()) {
            String instType = instrument.trim().toUpperCase();
            try {
                CurrentUserContext.runWithUserContext(userId, () -> {
                    try {
                        String instrumentToken = getInstrumentToken(instType);
                        if (instrumentToken == null) {
                            log.trace("No instrument token for {}, skipping candle refresh", instType);
                            return;
                        }

                        // Fetch today's 1-minute candles from market open
                        ZonedDateTime now = ZonedDateTime.now(IST);
                        ZonedDateTime marketOpen = now.withHour(9).withMinute(15).withSecond(0).withNano(0);

                        if (now.isBefore(marketOpen)) return;

                        Date from = Date.from(marketOpen.toInstant());
                        Date to = Date.from(now.toInstant());

                        HistoricalData data = tradingService.getHistoricalData(
                                from, to, instrumentToken, "minute", false, false);

                        if (data != null && data.dataArrayList != null && !data.dataArrayList.isEmpty()) {
                            String candleKey = instType + "_minute";
                            candleCache.put(candleKey,
                                    new CacheEntry<>(Collections.unmodifiableList(
                                            new ArrayList<>(data.dataArrayList))));
                            log.debug("Candle data refreshed for {}: {} candles", instType, data.dataArrayList.size());
                        }
                    } catch (KiteException | IOException e) {
                        log.warn("Failed to refresh candles for {}: {}", instType, e.getMessage());
                    }
                });
            } catch (Exception e) {
                log.warn("Error in candle refresh for {}: {}", instType, e.getMessage());
            }
        }
    }

    // ==================== COMPUTATION HELPERS ====================

    private BigDecimal computeVWAPFromCandles(List<HistoricalData> candles) {
        if (candles == null || candles.isEmpty()) return null;

        BigDecimal cumulativeTPV = BigDecimal.ZERO; // Typical Price × Volume
        BigDecimal cumulativeVolume = BigDecimal.ZERO;

        for (HistoricalData candle : candles) {
            double typicalPrice = (candle.high + candle.low + candle.close) / 3.0;
            long volume = candle.volume;
            if (volume <= 0) continue;

            BigDecimal tp = BigDecimal.valueOf(typicalPrice);
            BigDecimal vol = BigDecimal.valueOf(volume);
            cumulativeTPV = cumulativeTPV.add(tp.multiply(vol));
            cumulativeVolume = cumulativeVolume.add(vol);
        }

        if (cumulativeVolume.compareTo(BigDecimal.ZERO) == 0) return null;

        return cumulativeTPV.divide(cumulativeVolume, 4, RoundingMode.HALF_UP);
    }

    private Map<Double, double[]> extractMidPricesFromQuotes(Map<String, Quote> quotes,
                                                              String instrumentType,
                                                              Set<Double> strikes) {
        Map<Double, double[]> result = new HashMap<>(); // strike → [callMid, putMid]

        for (Map.Entry<String, Quote> entry : quotes.entrySet()) {
            Quote quote = entry.getValue();
            String symbol = entry.getKey();
            if (quote == null) continue;

            Double midPrice = extractMidPrice(quote);
            if (midPrice == null || midPrice <= 0) continue;

            Double strike = parseStrikeFromSymbol(symbol, instrumentType);
            if (strike == null || !strikes.contains(strike)) continue;

            double[] prices = result.computeIfAbsent(strike, k -> new double[]{0.0, 0.0});
            if (symbol.contains("CE")) {
                prices[0] = midPrice;
            } else if (symbol.contains("PE")) {
                prices[1] = midPrice;
            }
        }

        return result;
    }

    private Double extractMidPrice(Quote quote) {
        try {
            Double bestBid = null, bestAsk = null;
            if (quote.depth != null) {
                if (quote.depth.buy != null && !quote.depth.buy.isEmpty() && quote.depth.buy.get(0) != null) {
                    bestBid = quote.depth.buy.get(0).getPrice();
                }
                if (quote.depth.sell != null && !quote.depth.sell.isEmpty() && quote.depth.sell.get(0) != null) {
                    bestAsk = quote.depth.sell.get(0).getPrice();
                }
            }
            if (bestBid != null && bestAsk != null && bestBid > 0 && bestAsk > 0) {
                return (bestBid + bestAsk) / 2.0;
            }
            if (quote.lastPrice > 0) return quote.lastPrice;
        } catch (Exception e) {
            log.trace("Error extracting mid price: {}", e.getMessage());
        }
        return null;
    }

    private Double parseStrikeFromSymbol(String symbol, String instrumentType) {
        try {
            String symbolPart = symbol.contains(":") ? symbol.split(":")[1] : symbol;
            String prefix = instrumentType.toUpperCase();
            if (!symbolPart.startsWith(prefix)) return null;

            String remainder = symbolPart.substring(prefix.length());
            if (remainder.endsWith("CE") || remainder.endsWith("PE")) {
                remainder = remainder.substring(0, remainder.length() - 2);
            }

            // Extract digits from the end
            StringBuilder digits = new StringBuilder();
            for (int i = remainder.length() - 1; i >= 0; i--) {
                char c = remainder.charAt(i);
                if (Character.isDigit(c)) {
                    digits.insert(0, c);
                } else {
                    break;
                }
            }
            return digits.isEmpty() ? null : Double.parseDouble(digits.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private double calculateImpliedForward(double spotPrice, Map<Double, double[]> midPrices, double timeToExpiry) {
        List<Double> forwardEstimates = new ArrayList<>();
        double discountFactor = Math.exp(RISK_FREE_RATE * timeToExpiry);

        for (Map.Entry<Double, double[]> entry : midPrices.entrySet()) {
            double strike = entry.getKey();
            double[] prices = entry.getValue();
            if (prices[0] > 0 && prices[1] > 0) {
                double forward = strike + (prices[0] - prices[1]) * discountFactor;
                forwardEstimates.add(forward);
            }
        }

        if (forwardEstimates.isEmpty()) {
            return spotPrice * Math.exp(RISK_FREE_RATE * timeToExpiry);
        }

        Collections.sort(forwardEstimates);
        int size = forwardEstimates.size();
        return size % 2 == 1 ? forwardEstimates.get(size / 2)
                : (forwardEstimates.get(size / 2 - 1) + forwardEstimates.get(size / 2)) / 2.0;
    }

    private double solveIV(double callPrice, double forward, double strike, double T) {
        if (T <= 0 || callPrice <= 0) return 0.0;
        double discountFactor = Math.exp(-RISK_FREE_RATE * T);
        double intrinsic = Math.max(0, forward - strike) * discountFactor;
        if (callPrice <= intrinsic * 1.001) return 0.01;

        double sigma = 0.2;
        double sqrtT = Math.sqrt(T);

        for (int i = 0; i < 50; i++) {
            double d1 = (Math.log(forward / strike) + 0.5 * sigma * sigma * T) / (sigma * sqrtT);
            double d2 = d1 - sigma * sqrtT;
            double nd1 = cumulativeNormalDistribution(d1);
            double nd2 = cumulativeNormalDistribution(d2);
            double theoreticalPrice = discountFactor * (forward * nd1 - strike * nd2);
            double vega = discountFactor * forward * normalPDF(d1) * sqrtT;
            double priceDiff = theoreticalPrice - callPrice;

            if (Math.abs(priceDiff) < 0.01) return sigma;
            if (vega < 1e-10) break;

            sigma = sigma - 0.5 * priceDiff / vega;
            sigma = Math.max(0.01, Math.min(3.0, sigma));
        }
        return sigma;
    }

    private double findStrikeForDelta(Map<Double, Double> deltaMap, double targetDelta,
                                       boolean isCE, double approximateATM) {
        double bestStrike = approximateATM;
        double minDiff = Double.MAX_VALUE;

        for (Map.Entry<Double, Double> e : deltaMap.entrySet()) {
            double strike = e.getKey();
            double callDelta = e.getValue();
            double effectiveDelta = isCE ? callDelta : (1.0 - callDelta);
            double diff = Math.abs(effectiveDelta - targetDelta);

            // For OTM targets (< 0.4), enforce correct OTM side
            if (targetDelta < 0.4) {
                if (isCE && strike < approximateATM) continue;
                if (!isCE && strike > approximateATM) continue;
            }

            if (diff < minDiff) {
                minDiff = diff;
                bestStrike = strike;
            }
        }
        return bestStrike;
    }

    private double calculateTimeToExpiry(Date expiry) {
        Calendar now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        Calendar expiryCal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        expiryCal.setTime(expiry);
        expiryCal.set(Calendar.HOUR_OF_DAY, 15);
        expiryCal.set(Calendar.MINUTE, 30);
        expiryCal.set(Calendar.SECOND, 0);

        long diffMs = expiryCal.getTimeInMillis() - now.getTimeInMillis();
        if (diffMs <= 0) return 0.0;
        return diffMs / (365.2425 * 24.0 * 60.0 * 60.0 * 1000.0);
    }

    // ==================== UTILITY ====================

    private String getActiveUserId() {
        Set<String> activeUsers = userSessionManager.getActiveUserIds();
        if (activeUsers.isEmpty()) {
            log.trace("MarketDataEngine: no active user sessions, skipping refresh cycle");
            return null;
        }
        return activeUsers.iterator().next();
    }

    private String[] buildIndexSymbols() {
        String[] instruments = config.getSupportedInstrumentsArray();
        String[] symbols = new String[instruments.length];
        for (int i = 0; i < instruments.length; i++) {
            symbols[i] = mapInstrumentToSymbol(instruments[i].trim());
        }
        return symbols;
    }

    private String mapInstrumentToSymbol(String instrumentType) {
        return switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> "NSE:NIFTY 50";
            case "BANKNIFTY" -> "NSE:NIFTY BANK";
            case "SENSEX" -> "BSE:SENSEX";
            default -> "NSE:" + instrumentType;
        };
    }

    private String mapToUnderlyingName(String instrumentType) {
        return switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> INSTRUMENT_NIFTY;
            case "BANKNIFTY" -> INSTRUMENT_BANKNIFTY;
            default -> instrumentType.toUpperCase();
        };
    }

    private double getStrikeInterval(String instrumentType) {
        return switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> 50.0;
            case "BANKNIFTY" -> 100.0;
            default -> 50.0;
        };
    }

    private String getInstrumentToken(String instrumentType) {
        // Use well-known instrument tokens for major indices
        return switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> "256265";   // NSE:NIFTY 50
            case "BANKNIFTY" -> "260105"; // NSE:NIFTY BANK
            default -> null;
        };
    }

    private String buildStrikeByDeltaKey(String instrumentType, double targetDelta, String optionType) {
        // Use long representation to avoid floating-point key issues
        long deltaScaled = Math.round(targetDelta * 1000);
        return instrumentType.toUpperCase() + "_" + deltaScaled + "_" + optionType;
    }

    private boolean isNearestWeeklyExpiry(Date expiry) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        cal.setTime(expiry);
        if (cal.get(Calendar.DAY_OF_WEEK) != Calendar.THURSDAY) return false;

        long diffInDays = (expiry.getTime() - System.currentTimeMillis()) / (24 * 60 * 60 * 1000);
        return diffInDays >= 0 && diffInDays <= 7;
    }

    private boolean isMarketHours() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        int hour = now.getHour();
        int minute = now.getMinute();
        int dayOfWeek = now.getDayOfWeek().getValue();

        if (dayOfWeek > 5) return false; // Weekend

        int currentMinutes = hour * 60 + minute;
        int marketOpen = 9 * 60 + 15;   // 9:15 AM
        int marketClose = 15 * 60 + 30;  // 3:30 PM

        return currentMinutes >= marketOpen && currentMinutes <= marketClose;
    }

    private double cumulativeNormalDistribution(double x) {
        return 0.5 * (1.0 + erf(x / Math.sqrt(2.0)));
    }

    private double erf(double x) {
        double a1 = 0.254829592, a2 = -0.284496736, a3 = 1.421413741;
        double a4 = -1.453152027, a5 = 1.061405429, p = 0.3275911;
        int sign = x < 0 ? -1 : 1;
        x = Math.abs(x);
        double t = 1.0 / (1.0 + p * x);
        double y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-x * x);
        return sign * y;
    }

    private double normalPDF(double x) {
        return (1.0 / Math.sqrt(2.0 * Math.PI)) * Math.exp(-0.5 * x * x);
    }

    // ==================== CACHE ENTRY ====================

    /**
     * Immutable cache entry with value and timestamp.
     * Thread-safe: all fields are final, published via ConcurrentHashMap.put().
     *
     * @param <T> Type of cached value
     */
    public static final class CacheEntry<T> {
        public final T value;
        public final long timestampMs;

        public CacheEntry(T value) {
            this.value = value;
            this.timestampMs = System.currentTimeMillis();
        }

        /**
         * Check if entry is expired based on the given TTL.
         */
        public boolean isExpired(long ttlMs) {
            return System.currentTimeMillis() - timestampMs > ttlMs;
        }

        /**
         * Age of this entry in milliseconds.
         */
        public long ageMs() {
            return System.currentTimeMillis() - timestampMs;
        }
    }
}










