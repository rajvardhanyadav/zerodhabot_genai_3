package com.tradingbot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate Limiter Service for Kite Connect API calls.
 *
 * Implements rate limiting based on Kite API documentation:
 * - 3 requests per second per API endpoint
 * - 10 requests per second overall per API key
 *
 * Uses a token bucket algorithm with sliding window for accurate rate limiting.
 * Thread-safe and optimized for HFT operations.
 *
 * @see <a href="https://kite.trade/docs/connect/v3/exceptions/">Kite API Rate Limits</a>
 */
@Service
@Slf4j
public class RateLimiterService {

    // Kite API rate limits
    private static final int PER_API_LIMIT = 3;           // 3 requests per second per API
    private static final int GLOBAL_LIMIT = 10;           // 10 requests per second overall
    private static final long WINDOW_MS = 1000;           // 1 second window
    private static final long ACQUIRE_TIMEOUT_MS = 5000;  // 5 second timeout for acquiring permit

    // API endpoint categories for per-API rate limiting
    public enum ApiType {
        QUOTE,          // getQuote, getLTP, getOHLC
        ORDER,          // placeOrder, modifyOrder, cancelOrder
        INSTRUMENTS,    // getInstruments
        HISTORICAL,     // getHistoricalData
        POSITIONS,      // getPositions
        HOLDINGS,       // getHoldings
        ORDERS,         // getOrders, getOrderHistory
        GTT,            // GTT related
        MARGINS,        // getMargins
        PROFILE,        // getProfile
        OTHER           // Catch-all
    }

    // Per-API rate limiters using sliding window counters
    private final Map<ApiType, SlidingWindowRateLimiter> apiLimiters = new ConcurrentHashMap<>();

    // Global rate limiter
    private final SlidingWindowRateLimiter globalLimiter;

    // Semaphore for global concurrency control
    private final Semaphore globalSemaphore;

    public RateLimiterService() {
        this.globalLimiter = new SlidingWindowRateLimiter(GLOBAL_LIMIT, WINDOW_MS);
        this.globalSemaphore = new Semaphore(GLOBAL_LIMIT);

        // Initialize per-API limiters
        for (ApiType type : ApiType.values()) {
            apiLimiters.put(type, new SlidingWindowRateLimiter(PER_API_LIMIT, WINDOW_MS));
        }

        log.info("RateLimiterService initialized with per-API limit: {}/sec, global limit: {}/sec",
                PER_API_LIMIT, GLOBAL_LIMIT);
    }

    /**
     * Acquire a permit to make an API call.
     * Blocks until a permit is available or timeout is reached.
     *
     * @param apiType The type of API being called
     * @return true if permit was acquired, false if timeout exceeded
     */
    public boolean acquire(ApiType apiType) {
        return acquire(apiType, ACQUIRE_TIMEOUT_MS);
    }

    /**
     * Acquire a permit with custom timeout.
     *
     * @param apiType The type of API being called
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return true if permit was acquired, false if timeout exceeded
     */
    public boolean acquire(ApiType apiType, long timeoutMs) {
        long startTime = System.currentTimeMillis();
        long deadline = startTime + timeoutMs;

        try {
            // First check global limit
            while (System.currentTimeMillis() < deadline) {
                if (globalLimiter.tryAcquire()) {
                    // Now check per-API limit
                    SlidingWindowRateLimiter apiLimiter = apiLimiters.get(apiType);
                    if (apiLimiter != null && apiLimiter.tryAcquire()) {
                        if (log.isDebugEnabled()) {
                            log.debug("Rate limit permit acquired for {} in {}ms",
                                    apiType, System.currentTimeMillis() - startTime);
                        }
                        return true;
                    } else {
                        // Release global permit since API-specific failed
                        globalLimiter.release();
                    }
                }

                // Wait a bit before retrying
                long remainingTime = deadline - System.currentTimeMillis();
                if (remainingTime > 0) {
                    Thread.sleep(Math.min(50, remainingTime)); // Wait 50ms or remaining time
                }
            }

            log.warn("Rate limit timeout after {}ms for API type: {}", timeoutMs, apiType);
            return false;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Rate limit acquisition interrupted for API type: {}", apiType);
            return false;
        }
    }

    /**
     * Try to acquire permit immediately without blocking.
     *
     * @param apiType The type of API being called
     * @return true if permit was acquired immediately, false otherwise
     */
    public boolean tryAcquire(ApiType apiType) {
        if (globalLimiter.tryAcquire()) {
            SlidingWindowRateLimiter apiLimiter = apiLimiters.get(apiType);
            if (apiLimiter != null && apiLimiter.tryAcquire()) {
                return true;
            }
            globalLimiter.release();
        }
        return false;
    }

    /**
     * Execute an API call with rate limiting.
     * Automatically acquires and manages permits.
     *
     * @param apiType The type of API being called
     * @param apiCall The API call to execute
     * @param <T> Return type of the API call
     * @return Result of the API call
     * @throws RateLimitExceededException if rate limit cannot be acquired within timeout
     * @throws Exception if the API call throws an exception
     */
    public <T> T executeWithRateLimit(ApiType apiType, ApiCall<T> apiCall) throws Exception {
        if (!acquire(apiType)) {
            throw new RateLimitExceededException(
                    "Rate limit exceeded for API type: " + apiType + ". Please retry after some time.");
        }

        try {
            return apiCall.execute();
        } catch (Exception e) {
            // Log and rethrow
            log.error("API call failed for type {}: {}", apiType, e.getMessage());
            throw e;
        }
    }

    /**
     * Get current rate limit statistics for monitoring.
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put("globalRequestsInWindow", globalLimiter.getRequestCount());
        stats.put("perApiLimits", apiLimiters.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        e -> e.getKey().name(),
                        e -> e.getValue().getRequestCount()
                )));
        return stats;
    }

    /**
     * Functional interface for API calls.
     */
    @FunctionalInterface
    public interface ApiCall<T> {
        T execute() throws Exception;
    }

    /**
     * Custom exception for rate limit exceeded scenarios.
     */
    public static class RateLimitExceededException extends RuntimeException {
        public RateLimitExceededException(String message) {
            super(message);
        }
    }

    /**
     * Sliding window rate limiter implementation.
     * Thread-safe and provides accurate rate limiting.
     */
    private static class SlidingWindowRateLimiter {
        private final int maxRequests;
        private final long windowMs;
        private final AtomicLong[] timestamps;
        private final java.util.concurrent.atomic.AtomicInteger index;
        private final Object lock = new Object();

        SlidingWindowRateLimiter(int maxRequests, long windowMs) {
            this.maxRequests = maxRequests;
            this.windowMs = windowMs;
            this.timestamps = new AtomicLong[maxRequests];
            this.index = new java.util.concurrent.atomic.AtomicInteger(0);

            // Initialize timestamps to 0 (epoch)
            for (int i = 0; i < maxRequests; i++) {
                timestamps[i] = new AtomicLong(0);
            }
        }

        boolean tryAcquire() {
            long now = System.currentTimeMillis();
            long windowStart = now - windowMs;

            synchronized (lock) {
                // Count requests in current window
                int count = 0;
                int oldestIndex = -1;
                long oldestTime = Long.MAX_VALUE;

                for (int i = 0; i < maxRequests; i++) {
                    long ts = timestamps[i].get();
                    if (ts > windowStart) {
                        count++;
                    } else if (ts < oldestTime) {
                        oldestTime = ts;
                        oldestIndex = i;
                    }
                }

                if (count < maxRequests && oldestIndex >= 0) {
                    timestamps[oldestIndex].set(now);
                    return true;
                }

                return false;
            }
        }

        void release() {
            // Optional: Can be used to release a permit early
            // For sliding window, this is typically a no-op
        }

        int getRequestCount() {
            long now = System.currentTimeMillis();
            long windowStart = now - windowMs;
            int count = 0;
            for (int i = 0; i < maxRequests; i++) {
                if (timestamps[i].get() > windowStart) {
                    count++;
                }
            }
            return count;
        }
    }
}

