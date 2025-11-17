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
    private double defaultStopLossPoints = 10.0;
    private double defaultTargetPoints = 15.0;

    // Intraday scalping defaults (used when per-request values are not provided)
    private double scalpingStopLossPoints = 5.0;
    private double scalpingTargetPoints = 8.0;
    private double scalpingMaxLossPerTrade = 2000.0;

    /**
     * Allow live-mode executions for scalping strategy.
     * Recommended to keep false until validated thoroughly in paper trading.
     */
    private boolean scalpingEnabledInLive = false;

    // Auto square-off configuration
    private boolean autoSquareOffEnabled = false;
    private String autoSquareOffTime = "15:15"; // HH:mm format

    // Auto-restart configuration
    /** Enable/disable auto restart globally when target/SL is hit. */
    private boolean autoRestartEnabled = false;

    /**
     * Enable auto-restart when trading in LIVE mode.
     * Recommended to keep this false until behavior is validated in paper.
     */
    private boolean autoRestartLiveEnabled = false;

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
