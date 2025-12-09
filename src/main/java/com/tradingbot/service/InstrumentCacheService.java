package com.tradingbot.service;

import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Instrument Cache Service for reducing API calls to Kite.
 *
 * The getInstruments() API returns a large dataset (~100K+ instruments) and
 * doesn't change frequently during the day. This service caches the instrument
 * data and refreshes it periodically to avoid rate limit issues.
 *
 * Key Features:
 * - Cache per exchange (NFO, NSE, BSE, etc.)
 * - Automatic refresh every 5 minutes during market hours
 * - Thread-safe access with read-write locks
 * - Reduces getInstruments() calls from ~dozens/minute to ~1/5 minutes
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InstrumentCacheService {

    private final TradingService tradingService;

    // Cache TTL in milliseconds (5 minutes)
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;

    // Per-exchange instrument cache
    private final Map<String, CachedInstruments> exchangeCache = new ConcurrentHashMap<>();

    // All instruments cache
    private volatile CachedInstruments allInstrumentsCache;

    // Lock for thread-safe updates
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Get instruments for a specific exchange, using cache if available.
     *
     * @param exchange Exchange name (e.g., "NFO", "NSE")
     * @return List of instruments for the exchange
     */
    public List<Instrument> getInstruments(String exchange) throws KiteException, IOException {
        CachedInstruments cached = exchangeCache.get(exchange);

        if (cached != null && !cached.isExpired()) {
            log.debug("Instrument cache HIT for exchange: {} (age: {}ms)",
                    exchange, System.currentTimeMillis() - cached.timestamp);
            return cached.instruments;
        }

        log.info("Instrument cache MISS for exchange: {} - fetching from API", exchange);
        return refreshExchangeCache(exchange);
    }

    /**
     * Get all instruments, using cache if available.
     */
    public List<Instrument> getAllInstruments() throws KiteException, IOException {
        CachedInstruments cached = allInstrumentsCache;

        if (cached != null && !cached.isExpired()) {
            log.debug("All instruments cache HIT (age: {}ms)",
                    System.currentTimeMillis() - cached.timestamp);
            return cached.instruments;
        }

        log.info("All instruments cache MISS - fetching from API");
        return refreshAllInstrumentsCache();
    }

    /**
     * Get a specific instrument by trading symbol and exchange.
     */
    public Optional<Instrument> findInstrument(String exchange, String tradingSymbol)
            throws KiteException, IOException {
        List<Instrument> instruments = getInstruments(exchange);
        return instruments.stream()
                .filter(i -> tradingSymbol.equals(i.tradingsymbol))
                .findFirst();
    }

    /**
     * Force refresh the cache for a specific exchange.
     */
    public List<Instrument> refreshExchangeCache(String exchange) throws KiteException, IOException {
        lock.writeLock().lock();
        try {
            // Double-check after acquiring lock
            CachedInstruments cached = exchangeCache.get(exchange);
            if (cached != null && !cached.isExpired()) {
                return cached.instruments;
            }

            log.info("Refreshing instrument cache for exchange: {}", exchange);
            long startTime = System.currentTimeMillis();

            List<Instrument> instruments = tradingService.getInstruments(exchange);

            if (instruments != null && !instruments.isEmpty()) {
                exchangeCache.put(exchange, new CachedInstruments(instruments));
                log.info("Instrument cache refreshed for {}: {} instruments in {}ms",
                        exchange, instruments.size(), System.currentTimeMillis() - startTime);
                return instruments;
            }

            return Collections.emptyList();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Force refresh the all instruments cache.
     */
    public List<Instrument> refreshAllInstrumentsCache() throws KiteException, IOException {
        lock.writeLock().lock();
        try {
            // Double-check after acquiring lock
            CachedInstruments cached = allInstrumentsCache;
            if (cached != null && !cached.isExpired()) {
                return cached.instruments;
            }

            log.info("Refreshing all instruments cache");
            long startTime = System.currentTimeMillis();

            List<Instrument> instruments = tradingService.getInstruments();

            if (instruments != null && !instruments.isEmpty()) {
                allInstrumentsCache = new CachedInstruments(instruments);
                log.info("All instruments cache refreshed: {} instruments in {}ms",
                        instruments.size(), System.currentTimeMillis() - startTime);
                return instruments;
            }

            return Collections.emptyList();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Scheduled task to pre-warm the NFO instruments cache during market hours.
     * Runs every 5 minutes to keep the cache fresh.
     */
    @Scheduled(fixedRate = 300000, initialDelay = 60000) // 5 minutes
    public void prewarmNfoCache() {
        if (!isMarketHours()) {
            log.trace("Skipping NFO cache prewarm outside market hours");
            return;
        }

        try {
            // Only prewarm if cache is about to expire (within 1 minute of TTL)
            CachedInstruments cached = exchangeCache.get("NFO");
            if (cached != null && (System.currentTimeMillis() - cached.timestamp) < (CACHE_TTL_MS - 60000)) {
                log.debug("NFO cache still fresh, skipping prewarm");
                return;
            }

            log.info("Pre-warming NFO instrument cache");
            refreshExchangeCache("NFO");
        } catch (KiteException | IOException e) {
            log.error("Failed to prewarm NFO cache: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Failed to prewarm NFO cache: {}", e.getMessage());
        }
    }

    /**
     * Clear all cached instruments.
     */
    public void clearCache() {
        lock.writeLock().lock();
        try {
            exchangeCache.clear();
            allInstrumentsCache = null;
            log.info("Instrument cache cleared");
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get cache statistics for monitoring.
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put("totalExchangesCached", exchangeCache.size());

        exchangeCache.forEach((exchange, cached) -> {
            Map<String, Object> exchangeStats = new ConcurrentHashMap<>();
            exchangeStats.put("instrumentCount", cached.instruments.size());
            exchangeStats.put("ageMs", System.currentTimeMillis() - cached.timestamp);
            exchangeStats.put("expired", cached.isExpired());
            stats.put(exchange, exchangeStats);
        });

        if (allInstrumentsCache != null) {
            Map<String, Object> allStats = new ConcurrentHashMap<>();
            allStats.put("instrumentCount", allInstrumentsCache.instruments.size());
            allStats.put("ageMs", System.currentTimeMillis() - allInstrumentsCache.timestamp);
            allStats.put("expired", allInstrumentsCache.isExpired());
            stats.put("ALL", allStats);
        }

        return stats;
    }

    private boolean isMarketHours() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(
                java.time.ZoneId.of("Asia/Kolkata"));
        int hour = now.getHour();
        int dayOfWeek = now.getDayOfWeek().getValue();

        // Market hours: Monday-Friday, 9:00 AM to 4:00 PM IST
        return dayOfWeek >= 1 && dayOfWeek <= 5 && hour >= 9 && hour < 16;
    }

    /**
     * Internal class to hold cached instruments with timestamp.
     */
    private static class CachedInstruments {
        final List<Instrument> instruments;
        final long timestamp;

        CachedInstruments(List<Instrument> instruments) {
            this.instruments = Collections.unmodifiableList(instruments);
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }
}

