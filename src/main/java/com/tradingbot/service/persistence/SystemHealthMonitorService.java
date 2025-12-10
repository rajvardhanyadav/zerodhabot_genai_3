package com.tradingbot.service.persistence;

import com.tradingbot.config.PersistenceConfig;
import com.tradingbot.entity.SystemHealthSnapshotEntity;
import com.tradingbot.service.StrategyService;
import com.tradingbot.service.strategy.monitoring.WebSocketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.sql.Connection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for monitoring and persisting system health metrics.
 *
 * HFT Optimizations:
 * 1. Non-blocking atomic counters for tick/order recording
 * 2. Async DB health check (doesn't block snapshot capture)
 * 3. Uses PersistenceBufferService for write-behind caching
 */
@Service
@Slf4j
public class SystemHealthMonitorService {

    private final PersistenceConfig persistenceConfig;
    private final PersistenceBufferService persistenceBufferService;
    private final DataSource dataSource;

    @Autowired
    @Lazy
    private WebSocketService webSocketService;

    @Autowired
    @Lazy
    private StrategyService strategyService;

    // Metrics counters - all use atomic operations for thread-safety without locks
    private final AtomicLong ticksReceived = new AtomicLong(0);
    private final AtomicLong ordersProcessed = new AtomicLong(0);
    private final AtomicLong totalOrderLatencyNanos = new AtomicLong(0);
    private final AtomicLong orderCount = new AtomicLong(0);
    private final AtomicLong maxOrderLatencyNanos = new AtomicLong(0);
    private final AtomicLong paperOrdersToday = new AtomicLong(0);
    private final AtomicLong liveOrdersToday = new AtomicLong(0);

    // Cached DB health status (updated asynchronously)
    private final AtomicBoolean lastKnownDbHealthy = new AtomicBoolean(true);

    public SystemHealthMonitorService(PersistenceConfig persistenceConfig,
                                       PersistenceBufferService persistenceBufferService,
                                       DataSource dataSource) {
        this.persistenceConfig = persistenceConfig;
        this.persistenceBufferService = persistenceBufferService;
        this.dataSource = dataSource;
    }

    /**
     * Record a tick received (called from WebSocket tick handler)
     * HFT-SAFE: Single atomic increment, no locks, no allocations
     */
    public void recordTick() {
        ticksReceived.incrementAndGet();
    }

    /**
     * Record an order processed with latency
     * HFT-SAFE: Lock-free CAS operations only
     */
    public void recordOrderProcessed(long latencyNanos, boolean isPaper) {
        ordersProcessed.incrementAndGet();
        totalOrderLatencyNanos.addAndGet(latencyNanos);
        orderCount.incrementAndGet();

        // Update max latency using CAS loop
        long currentMax;
        do {
            currentMax = maxOrderLatencyNanos.get();
        } while (latencyNanos > currentMax && !maxOrderLatencyNanos.compareAndSet(currentMax, latencyNanos));

        if (isPaper) {
            paperOrdersToday.incrementAndGet();
        } else {
            liveOrdersToday.incrementAndGet();
        }
    }

    /**
     * Scheduled job to capture health snapshot every minute
     * Uses non-blocking buffer service for persistence
     */
    @Scheduled(fixedRate = 60000)
    public void captureHealthSnapshot() {
        if (!persistenceConfig.isEnabled()) {
            return;
        }

        try {
            // Memory metrics (fast, no I/O)
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            long heapUsed = memoryBean.getHeapMemoryUsage().getUsed() / (1024 * 1024);
            long heapMax = memoryBean.getHeapMemoryUsage().getMax() / (1024 * 1024);
            long nonHeapUsed = memoryBean.getNonHeapMemoryUsage().getUsed() / (1024 * 1024);

            // Thread metrics (fast, no I/O)
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            int activeThreads = threadBean.getThreadCount();
            int peakThreads = threadBean.getPeakThreadCount();

            // Connection status (use cached values, avoid blocking calls)
            boolean wsConnected = false;
            int wsSubscriptions = 0;
            try {
                wsConnected = webSocketService != null && webSocketService.isConnected();
                wsSubscriptions = webSocketService != null ? webSocketService.getActiveMonitorsCount() : 0;
            } catch (Exception e) {
                log.trace("Error getting WebSocket status: {}", e.getMessage());
            }

            // Strategy metrics
            int activeStrategies = 0;
            try {
                activeStrategies = strategyService != null ? strategyService.getActiveStrategies().size() : 0;
            } catch (Exception e) {
                log.trace("Error getting strategy count: {}", e.getMessage());
            }

            // Performance metrics - get and reset counters atomically
            long ticks = ticksReceived.getAndSet(0);
            long orders = ordersProcessed.getAndSet(0);
            long count = orderCount.getAndSet(0);
            long totalLatency = totalOrderLatencyNanos.getAndSet(0);
            long maxLatency = maxOrderLatencyNanos.getAndSet(0);

            double avgLatencyMs = count > 0 ? (totalLatency / count) / 1_000_000.0 : 0.0;
            double maxLatencyMs = maxLatency / 1_000_000.0;

            // Trigger async DB health check (non-blocking)
            checkDatabaseHealthAsync();

            // Get active DB connections (fast for HikariCP)
            int activeDbConnections = getActiveDbConnections();

            // Create snapshot entity
            SystemHealthSnapshotEntity snapshot = SystemHealthSnapshotEntity.builder()
                    .heapMemoryUsedMB(heapUsed)
                    .heapMemoryMaxMB(heapMax)
                    .nonHeapMemoryUsedMB(nonHeapUsed)
                    .activeThreads(activeThreads)
                    .peakThreads(peakThreads)
                    .kiteConnected(true)
                    .websocketConnected(wsConnected)
                    .activeWebSocketSubscriptions(wsSubscriptions)
                    .activeStrategies(activeStrategies)
                    .completedStrategiesToday(0)
                    .ticksReceivedLastMinute(ticks)
                    .ordersProcessedLastMinute(orders)
                    .avgOrderLatencyMs(avgLatencyMs)
                    .maxOrderLatencyMs(maxLatencyMs)
                    .databaseHealthy(lastKnownDbHealthy.get())
                    .activeDbConnections(activeDbConnections)
                    .paperOrdersToday((int) paperOrdersToday.get())
                    .liveOrdersToday((int) liveOrdersToday.get())
                    .build();

            // Buffer for async persistence (non-blocking)
            persistenceBufferService.bufferHealthSnapshot(snapshot);

            log.debug("Captured system health snapshot: heap={}MB, threads={}, ws={}, strategies={}",
                    heapUsed, activeThreads, wsConnected, activeStrategies);

        } catch (Exception e) {
            log.error("Failed to capture health snapshot: {}", e.getMessage());
        }
    }

    /**
     * Reset daily counters (called at start of trading day)
     */
    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void resetDailyCounters() {
        paperOrdersToday.set(0);
        liveOrdersToday.set(0);
        log.info("Reset daily order counters");
    }

    /**
     * Async DB health check - doesn't block the main thread
     */
    @Async("persistenceExecutor")
    public CompletableFuture<Boolean> checkDatabaseHealthAsync() {
        try (Connection conn = dataSource.getConnection()) {
            boolean healthy = conn.isValid(2);
            lastKnownDbHealthy.set(healthy);
            return CompletableFuture.completedFuture(healthy);
        } catch (Exception e) {
            lastKnownDbHealthy.set(false);
            log.warn("Database health check failed: {}", e.getMessage());
            return CompletableFuture.completedFuture(false);
        }
    }

    private int getActiveDbConnections() {
        try {
            if (dataSource instanceof com.zaxxer.hikari.HikariDataSource hikariDs) {
                return hikariDs.getHikariPoolMXBean().getActiveConnections();
            }
        } catch (Exception e) {
            log.trace("Could not get active DB connections: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * Get current metrics without resetting
     */
    public HealthMetrics getCurrentMetrics() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

        return HealthMetrics.builder()
                .heapUsedMB(memoryBean.getHeapMemoryUsage().getUsed() / (1024 * 1024))
                .heapMaxMB(memoryBean.getHeapMemoryUsage().getMax() / (1024 * 1024))
                .activeThreads(threadBean.getThreadCount())
                .ticksLastMinute(ticksReceived.get())
                .ordersLastMinute(ordersProcessed.get())
                .paperOrdersToday(paperOrdersToday.get())
                .liveOrdersToday(liveOrdersToday.get())
                .build();
    }

    @lombok.Data
    @lombok.Builder
    public static class HealthMetrics {
        private long heapUsedMB;
        private long heapMaxMB;
        private int activeThreads;
        private long ticksLastMinute;
        private long ordersLastMinute;
        private long paperOrdersToday;
        private long liveOrdersToday;
    }
}

