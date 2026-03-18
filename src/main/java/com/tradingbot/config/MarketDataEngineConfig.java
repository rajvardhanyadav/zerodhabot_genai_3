package com.tradingbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the MarketDataEngine — centralized, high-performance
 * market data service responsible for pre-computing and caching all
 * market data operations so strategy execution reads from cache only.
 *
 * <h2>Design Goals</h2>
 * <ul>
 *   <li>All expensive operations (option chain, delta, VWAP) are pre-computed on a scheduled cycle</li>
 *   <li>Strategy execution code should ONLY read from cache — never trigger a live fetch inline</li>
 *   <li>TTLs are tuned per data type: spot price (1s), delta (30s), option chain (60s), VWAP (5s)</li>
 * </ul>
 *
 * @since 4.2
 */
@Configuration
@ConfigurationProperties(prefix = "market-data-engine")
@Data
public class MarketDataEngineConfig {

    /**
     * Master switch: enable/disable the MarketDataEngine background refresh.
     * When disabled, strategies fall back to inline API calls (legacy behavior).
     */
    private boolean enabled = true;

    // ==================== REFRESH INTERVALS (milliseconds) ====================

    /**
     * Spot price refresh interval.
     * Index prices (NIFTY/SENSEX) refresh at this rate.
     * Default: 1000ms (1 second) — critical for ATM strike accuracy.
     */
    private long spotPriceRefreshMs = 1000;

    /**
     * Option chain refresh interval.
     * Full option chain for supported indices refreshes at this rate.
     * Default: 60000ms (60 seconds) — instruments rarely change intraday.
     */
    private long optionChainRefreshMs = 60000;

    /**
     * Delta pre-computation refresh interval.
     * ATM strike and per-strike deltas are recomputed at this rate.
     * Default: 5000ms (5 seconds) — balances accuracy vs API budget.
     */
    private long deltaRefreshMs = 5000;

    /**
     * VWAP refresh interval.
     * Session VWAP is recalculated from candle data at this rate.
     * Default: 5000ms (5 seconds).
     */
    private long vwapRefreshMs = 5000;

    /**
     * Candle data (OHLCV) refresh interval.
     * Default: 60000ms (60 seconds) — 1-minute candle granularity.
     */
    private long candleRefreshMs = 60000;

    // ==================== CACHE TTLs (milliseconds) ====================

    /**
     * Spot price cache TTL. After this duration without refresh, data is considered stale.
     * Default: 2000ms (2 seconds) — tight for HFT; allows 1 missed cycle.
     */
    private long spotPriceTtlMs = 2000;

    /**
     * Option chain cache TTL.
     * Default: 120000ms (2 minutes) — instruments are static within a session.
     */
    private long optionChainTtlMs = 120000;

    /**
     * Delta cache TTL. Strategy will use stale delta up to this age.
     * Default: 10000ms (10 seconds) — allows 2 missed refresh cycles.
     */
    private long deltaTtlMs = 10000;

    /**
     * VWAP cache TTL.
     * Default: 10000ms (10 seconds).
     */
    private long vwapTtlMs = 10000;

    /**
     * Candle data cache TTL.
     * Default: 120000ms (2 minutes).
     */
    private long candleTtlMs = 120000;

    // ==================== ENGINE THREAD POOL ====================

    /**
     * Number of threads for the MarketDataEngine background refresh pool.
     * Each data type runs its own scheduled task.
     * Default: 4 (spot, delta, VWAP, candles — option chain piggybacks on instrument cache).
     */
    private int threadPoolSize = 4;

    // ==================== SUPPORTED INSTRUMENTS ====================

    /**
     * Comma-separated list of supported index instruments.
     * Default: NIFTY
     */
    private String supportedInstruments = "NIFTY";

    // ==================== DELTA PRE-COMPUTATION ====================

    /**
     * Comma-separated list of target delta values to pre-compute strikes for.
     * Must cover all strategy requirements:
     * - 0.5: ATM straddle sell legs
     * - 0.4: Short strangle sell legs (shortStrangleSellDelta)
     * - 0.3: Near-ATM analysis
     * - 0.2: Moderate OTM analysis
     * - 0.15: OTM analysis
     * - 0.1: Hedge legs (sellStraddleHedgeDelta, shortStrangleHedgeDelta)
     * - 0.05: Far OTM hedge analysis
     * Default: "0.05,0.1,0.15,0.2,0.25,0.3,0.35,0.4,0.45,0.5"
     */
    private String deltaTargets = "0.05,0.1,0.15,0.2,0.25,0.3,0.35,0.4,0.45,0.5";

    /**
     * Number of strikes to scan on each side of ATM for near-ATM delta targets (≥ 0.3).
     * Default: 10
     */
    private int deltaStrikeRangeNearAtm = 10;

    /**
     * Number of strikes to scan on each side of ATM for far OTM delta targets (< 0.3).
     * Must be wide enough to find 0.1Δ and 0.05Δ strikes.
     * Default: 30
     */
    private int deltaStrikeRangeFarOtm = 30;

    /**
     * Returns the supported instruments as an array.
     */
    public String[] getSupportedInstrumentsArray() {
        return supportedInstruments.split(",");
    }

    /**
     * Returns the delta targets as a double array.
     */
    public double[] getDeltaTargetsArray() {
        String[] parts = deltaTargets.split(",");
        double[] targets = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            targets[i] = Double.parseDouble(parts[i].trim());
        }
        return targets;
    }
}
