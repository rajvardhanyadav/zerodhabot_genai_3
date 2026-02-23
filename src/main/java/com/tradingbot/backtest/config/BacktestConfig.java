package com.tradingbot.backtest.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the backtesting module.
 * All properties are prefixed with "backtest." in application.yml.
 */
@Configuration
@ConfigurationProperties(prefix = "backtest")
@Data
public class BacktestConfig {

    /** Global enable/disable switch for the backtest API. */
    private boolean enabled = true;

    /** Maximum number of backtest results to keep in memory cache. */
    private int maxCacheSize = 100;

    /** Thread pool size for async backtest execution. */
    private int asyncPoolSize = 4;

    /** Default candle interval for historical data fetch. "minute" is the smallest Kite offers. */
    private String defaultCandleInterval = "minute";

    /** Delay between Kite historical API calls to respect rate limits (3 req/sec). */
    private long rateLimitDelayMs = 350;
}

