package com.tradingbot.service.persistence;

import com.tradingbot.config.PersistenceConfig;
import com.tradingbot.service.StrategyService;
import com.tradingbot.service.strategy.monitoring.WebSocketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for monitoring and persisting system health metrics.
 * Captures memory, thread, connection, and performance statistics.
 */
@Service
@Slf4j
public class SystemHealthMonitorService {

    private final PersistenceConfig persistenceConfig;
    private final TradePersistenceService tradePersistenceService;
    private final DataSource dataSource;

    @Autowired
    @Lazy
    private WebSocketService webSocketService;

    @Autowired
    @Lazy
    private StrategyService strategyService;

    // Metrics counters
    private final AtomicLong ticksReceived = new AtomicLong(0);
    private final AtomicLong ordersProcessed = new AtomicLong(0);
    private final AtomicLong totalOrderLatencyNanos = new AtomicLong(0);
    private final AtomicLong orderCount = new AtomicLong(0);
    private final AtomicLong maxOrderLatencyNanos = new AtomicLong(0);
    private final AtomicLong paperOrdersToday = new AtomicLong(0);
    private final AtomicLong liveOrdersToday = new AtomicLong(0);

    public SystemHealthMonitorService(PersistenceConfig persistenceConfig,
                                       TradePersistenceService tradePersistenceService,
                                       DataSource dataSource) {
        this.persistenceConfig = persistenceConfig;
        this.tradePersistenceService = tradePersistenceService;
        this.dataSource = dataSource;
    }

    /**
     * Record a tick received (called from WebSocket tick handler)
     */
    public void recordTick() {
        ticksReceived.incrementAndGet();
    }

    /**
     * Record an order processed with latency
     */
    public void recordOrderProcessed(long latencyNanos, boolean isPaper) {
        ordersProcessed.incrementAndGet();
        totalOrderLatencyNanos.addAndGet(latencyNanos);
        orderCount.incrementAndGet();

        // Update max latency
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
     */
    @Scheduled(fixedRate = 60000) // Every minute
    public void captureHealthSnapshot() {
        if (!persistenceConfig.isEnabled()) {
            return;
        }

        try {
            // Memory metrics
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            long heapUsed = memoryBean.getHeapMemoryUsage().getUsed() / (1024 * 1024);
            long heapMax = memoryBean.getHeapMemoryUsage().getMax() / (1024 * 1024);
            long nonHeapUsed = memoryBean.getNonHeapMemoryUsage().getUsed() / (1024 * 1024);

            // Thread metrics
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            int activeThreads = threadBean.getThreadCount();
            int peakThreads = threadBean.getPeakThreadCount();

            // Connection status
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

            // Performance metrics - get and reset counters
            long ticks = ticksReceived.getAndSet(0);
            long orders = ordersProcessed.getAndSet(0);
            long count = orderCount.getAndSet(0);
            long totalLatency = totalOrderLatencyNanos.getAndSet(0);
            long maxLatency = maxOrderLatencyNanos.getAndSet(0);

            double avgLatencyMs = count > 0 ? (totalLatency / count) / 1_000_000.0 : 0.0;
            double maxLatencyMs = maxLatency / 1_000_000.0;

            // Database health
            boolean dbHealthy = checkDatabaseHealth();
            int activeDbConnections = getActiveDbConnections();

            // Persist the snapshot
            tradePersistenceService.persistSystemHealthSnapshotAsync(
                    heapUsed,
                    heapMax,
                    nonHeapUsed,
                    activeThreads,
                    peakThreads,
                    true, // Assume Kite is connected if we got this far
                    wsConnected,
                    wsSubscriptions,
                    activeStrategies,
                    0, // completedStrategiesToday - would need to track this
                    ticks,
                    orders,
                    avgLatencyMs,
                    maxLatencyMs,
                    dbHealthy,
                    activeDbConnections,
                    (int) paperOrdersToday.get(),
                    (int) liveOrdersToday.get()
            );

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

    private boolean checkDatabaseHealth() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(5);
        } catch (Exception e) {
            log.warn("Database health check failed: {}", e.getMessage());
            return false;
        }
    }

    private int getActiveDbConnections() {
        // This is a simplified version - actual implementation depends on connection pool
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

