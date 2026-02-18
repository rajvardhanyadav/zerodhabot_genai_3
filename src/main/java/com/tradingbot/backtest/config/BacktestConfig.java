package com.tradingbot.backtest.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Configuration for backtesting async operations.
 *
 * Provides a dedicated thread pool for backtest execution to:
 * - Prevent blocking the main application threads
 * - Allow concurrent backtest runs (with controlled parallelism)
 * - Isolate backtest execution from live trading operations
 */
@Configuration
@EnableAsync
@Slf4j
public class BacktestConfig {

    /**
     * Executor for async backtest operations.
     *
     * Thread pool sizing rationale:
     * - Core pool: 2 threads (backtests are I/O bound due to API calls)
     * - Max pool: 4 threads (prevent overwhelming the Kite API rate limits)
     * - Queue: 100 (buffer for batch backtests)
     */
    @Bean(name = "backtestExecutor")
    public Executor backtestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("Backtest-");
        executor.setRejectedExecutionHandler((r, e) -> {
            log.warn("Backtest task rejected, queue full. Consider reducing batch size.");
            throw new java.util.concurrent.RejectedExecutionException(
                    "Backtest queue full. Please wait for current backtests to complete.");
        });
        executor.initialize();
        log.info("Backtest executor initialized: corePool=2, maxPool=4, queue=100");
        return executor;
    }
}

