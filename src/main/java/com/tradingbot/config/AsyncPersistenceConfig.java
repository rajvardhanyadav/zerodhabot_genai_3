package com.tradingbot.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Configuration for asynchronous persistence operations.
 *
 * Uses a dedicated thread pool to ensure persistence operations
 * don't block the HFT trading hot path.
 */
@Configuration
@EnableAsync
@Slf4j
public class AsyncPersistenceConfig {

    /**
     * Dedicated executor for persistence operations.
     *
     * Configuration optimized for write-behind caching pattern:
     * - Core pool: 4 threads for normal load
     * - Max pool: 8 threads for burst handling
     * - Queue: 500 operations to buffer during peaks
     */
    @Bean(name = "persistenceExecutor")
    public Executor persistenceExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("persistence-");
        executor.setRejectedExecutionHandler((r, e) -> {
            log.warn("Persistence task rejected due to queue full. Consider increasing queue capacity.");
            // Run in caller thread as fallback (will block but ensures persistence)
            if (!e.isShutdown()) {
                r.run();
            }
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();

        log.info("Persistence executor initialized with core={}, max={}, queue={}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), 500);

        return executor;
    }
}

