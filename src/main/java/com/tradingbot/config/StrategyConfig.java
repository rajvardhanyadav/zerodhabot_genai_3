package com.tradingbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Strategy Configuration
 * Default values for strategy parameters like stop loss and target
 */
@Configuration
@ConfigurationProperties(prefix = "strategy")
@Data
public class StrategyConfig {

    // Default strategy parameters (can be overridden per execution)
    private double defaultStopLossPoints = 2.0;
    private double defaultTargetPoints = 2.0;

    // ==================== TRAILING STOP LOSS CONFIGURATION ====================

    /**
     * Enable trailing stop loss globally.
     * When enabled, stop loss will trail up as position becomes profitable.
     */
    private boolean trailingStopEnabled = false;

    /**
     * Activation threshold: trailing stop activates when cumulative P&L >= this value.
     * Example: 1.0 means trailing starts after 1 point profit.
     */
    private double trailingActivationPoints = 1.0;

    /**
     * Trail distance: how far behind the high-water mark the stop trails.
     * Example: 0.5 means stop is always 0.5 points below peak profit.
     */
    private double trailingDistancePoints = 0.5;

    // Auto square-off configuration
    private boolean autoSquareOffEnabled = false;
    private String autoSquareOffTime = "15:10"; // HH:mm format - forced exit time (IST)

    /**
     * Enable forced exit during backtesting.
     * When false, time-based exit is only active in live/paper trading.
     */
    private boolean autoSquareOffBacktestEnabled = true;

    // Auto-restart configuration
    /** Enable/disable auto restart globally when target/SL is hit. */
    private boolean autoRestartEnabled = true;

    /**
     * Enable auto-restart when trading in LIVE mode.
     * Recommended to keep this false until behavior is validated in paper.
     */
    private boolean autoRestartLiveEnabled = true;

    /**
     * Enable auto-restart when trading in PAPER mode.
     */
    private boolean autoRestartPaperEnabled = true;

    /**
     * Maximum number of chained auto restarts for a given execution lineage.
     * 0 or negative means unlimited (use with care for live trading).
     */
    private int maxAutoRestarts = 0;

    @Bean
    public TaskScheduler strategyTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("strategy-restart-");
        scheduler.initialize();
        return scheduler;
    }
}
