package com.tradingbot.config;

import com.tradingbot.service.persistence.PersistenceBufferService;
import com.tradingbot.util.CurrentUserContext;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Configuration for asynchronous persistence operations.
 *
 * HFT Optimizations:
 * 1. Dedicated thread pool isolated from trading threads
 * 2. CallerRunsPolicy disabled - drops tasks rather than blocking caller
 * 3. Large queue to absorb bursts without blocking
 * 4. Graceful shutdown with buffer flush
 *
 * CLOUD RUN COMPATIBILITY:
 * 5. TaskDecorator propagates user context to async threads
 *    - Captures CurrentUserContext from calling thread
 *    - Restores context in executor thread before task runs
 *    - Clears context after task completes to prevent leaks
 */
@Configuration
@EnableAsync
@Slf4j
public class AsyncPersistenceConfig {

    @Autowired
    @Lazy
    private PersistenceBufferService persistenceBufferService;

    /**
     * Dedicated executor for persistence operations.
     *
     * HFT-Optimized Configuration:
     * - Core pool: 2 threads (persistence is I/O bound, not CPU)
     * - Max pool: 4 threads for burst handling
     * - Queue: 1000 operations for large bursts
     * - Rejection: Log and drop (NEVER block trading thread)
     * - TaskDecorator: Propagates user context for Cloud Run compatibility
     */
    @Bean(name = "persistenceExecutor")
    public Executor persistenceExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("persist-");

        // CLOUD RUN: Propagate user context to async threads
        executor.setTaskDecorator(new UserContextPropagatingTaskDecorator());

        // HFT CRITICAL: Never block the caller thread
        // If queue is full, log and discard (trading must continue)
        executor.setRejectedExecutionHandler(new HFTSafeRejectionHandler());

        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();

        log.info("Persistence executor initialized: core={}, max={}, queue={} [HFT-SAFE: drops on overflow, CLOUD-RUN: context propagation enabled]",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), 1000);

        return executor;
    }

    /**
     * TaskDecorator that propagates CurrentUserContext to async threads.
     *
     * CLOUD RUN CRITICAL:
     * - In Cloud Run, executor threads are created at startup and reused
     * - InheritableThreadLocal does NOT work because threads aren't children of request threads
     * - This decorator explicitly captures and restores user context
     *
     * Thread Safety:
     * - Captures userId at task submission time (calling thread)
     * - Restores userId before task execution (executor thread)
     * - Clears context after task completes (prevents leaks on thread reuse)
     */
    private static class UserContextPropagatingTaskDecorator implements TaskDecorator {
        @Override
        public Runnable decorate(Runnable runnable) {
            // Capture context from the calling thread (request thread)
            String capturedUserId = CurrentUserContext.getUserId();

            return () -> {
                String previousUserId = CurrentUserContext.getUserId();
                try {
                    // Restore captured context in executor thread
                    if (capturedUserId != null && !capturedUserId.isBlank()) {
                        CurrentUserContext.setUserId(capturedUserId);
                        log.trace("Context propagated to async thread: userId={}, thread={}",
                                capturedUserId, Thread.currentThread().getName());
                    }
                    runnable.run();
                } finally {
                    // Restore previous context or clear (prevents leaks on thread reuse)
                    if (previousUserId != null && !previousUserId.isBlank()) {
                        CurrentUserContext.setUserId(previousUserId);
                    } else {
                        CurrentUserContext.clear();
                    }
                }
            };
        }
    }

    /**
     * HFT-Safe rejection handler that NEVER blocks the caller.
     * Logs the rejection and discards the task.
     */
    private static class HFTSafeRejectionHandler implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            // NEVER call r.run() here - that would block the trading thread
            log.warn("Persistence task dropped - queue full ({}/{}). " +
                     "Trading continues unaffected. Consider increasing queue size.",
                     executor.getQueue().size(), 1000);
        }
    }

    /**
     * Flush persistence buffers before shutdown
     */
    @PreDestroy
    public void onShutdown() {
        log.info("Shutting down - flushing persistence buffers...");
        try {
            if (persistenceBufferService != null) {
                persistenceBufferService.forceFlush();
            }
        } catch (Exception e) {
            log.error("Error flushing persistence buffers on shutdown: {}", e.getMessage());
        }
    }
}

