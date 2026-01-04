package com.tradingbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * Volatility Filter Configuration
 *
 * Controls the VIX-based volatility filter that determines whether straddle placement
 * is allowed based on India VIX conditions. This helps avoid unfavorable volatility
 * environments for short straddle strategies.
 *
 * Thread-safety: This class is immutable after Spring initialization (all fields
 * are set via property binding). Safe for concurrent access.
 */
@Configuration
@ConfigurationProperties(prefix = "volatility")
@Data
public class VolatilityConfig {

    /**
     * Master switch to enable/disable the volatility filter.
     * When disabled, straddle placement proceeds unconditionally.
     * Default: true (filter enabled)
     */
    private boolean enabled = true;

    /**
     * Symbol for India VIX in Kite format.
     * Used to fetch current VIX values via LTP/historical APIs.
     * Default: "NSE:INDIA VIX"
     */
    private String vixSymbol = "NSE:INDIA VIX";

    /**
     * Instrument token for India VIX (for historical data API).
     * This is the numeric identifier used by Kite's historical data endpoint.
     * Default: "264969" (NSE India VIX token)
     */
    private String vixInstrumentToken = "264969";

    /**
     * Absolute VIX threshold.
     * If current VIX > this value, straddle placement is allowed.
     * Rationale: Higher VIX means higher option premiums, better for sellers.
     * Default: 12.5
     */
    private BigDecimal absoluteThreshold = new BigDecimal("12.5");

    /**
     * 5-minute VIX percentage change threshold.
     * If VIX has increased by more than this percentage in the last 5 minutes,
     * straddle placement is allowed (volatility expansion = opportunity).
     * Default: 0.3 (0.3%)
     */
    private BigDecimal percentageChangeThreshold = new BigDecimal("0.3");

    /**
     * Enable volatility filter during backtesting/historical replay.
     * Set to false to preserve existing backtest behavior.
     * Default: false
     */
    private boolean backtestEnabled = false;

    /**
     * Fail-safe behavior when VIX data is unavailable.
     * When true, allows trade if VIX fetch fails (conservative = don't block trading).
     * When false, blocks trade if VIX data cannot be retrieved.
     * Default: true (allow trade on data unavailability)
     */
    private boolean allowOnDataUnavailable = true;

    /**
     * Cache TTL for VIX data in milliseconds.
     * Prevents excessive API calls by caching VIX values.
     * Default: 60000 (1 minute)
     */
    private long cacheTtlMs = 60000;
}

