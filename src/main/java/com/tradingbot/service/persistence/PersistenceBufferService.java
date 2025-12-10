package com.tradingbot.service.persistence;

import com.tradingbot.config.PersistenceConfig;
import com.tradingbot.entity.*;
import com.tradingbot.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HFT-Optimized Write-Behind Buffer for Persistence Operations.
 *
 * Key optimizations:
 * 1. Non-blocking queue for all persistence operations
 * 2. Batch writes to reduce database round-trips
 * 3. Circuit breaker to prevent cascade failures
 * 4. Rate limiting for high-frequency data (MTM snapshots)
 * 5. Separate queues for different priority levels
 */
@Service
@Slf4j
public class PersistenceBufferService {

    private final PersistenceConfig persistenceConfig;
    private final AlertHistoryRepository alertHistoryRepository;
    private final MTMSnapshotRepository mtmSnapshotRepository;
    private final WebSocketEventRepository webSocketEventRepository;
    private final SystemHealthSnapshotRepository systemHealthSnapshotRepository;

    // ==================== CIRCUIT BREAKER ====================
    private final AtomicBoolean circuitOpen = new AtomicBoolean(false);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private static final int CIRCUIT_BREAKER_THRESHOLD = 5;
    private static final long CIRCUIT_RESET_MS = 30_000; // 30 seconds

    // ==================== WRITE-BEHIND BUFFERS ====================
    // Using bounded queues to prevent memory issues under load
    private final BlockingQueue<AlertHistoryEntity> alertBuffer = new LinkedBlockingQueue<>(1000);
    private final BlockingQueue<MTMSnapshotEntity> mtmBuffer = new LinkedBlockingQueue<>(500);
    private final BlockingQueue<WebSocketEventEntity> wsEventBuffer = new LinkedBlockingQueue<>(200);
    private final BlockingQueue<SystemHealthSnapshotEntity> healthBuffer = new LinkedBlockingQueue<>(100);

    // ==================== RATE LIMITING FOR MTM ====================
    private final AtomicLong lastMtmSnapshotTime = new AtomicLong(0);
    private static final long MTM_MIN_INTERVAL_MS = 5000; // Max 1 MTM snapshot per 5 seconds per user

    // ==================== METRICS ====================
    private final AtomicLong bufferedCount = new AtomicLong(0);
    private final AtomicLong flushedCount = new AtomicLong(0);
    private final AtomicLong droppedCount = new AtomicLong(0);

    public PersistenceBufferService(PersistenceConfig persistenceConfig,
                                     AlertHistoryRepository alertHistoryRepository,
                                     MTMSnapshotRepository mtmSnapshotRepository,
                                     WebSocketEventRepository webSocketEventRepository,
                                     SystemHealthSnapshotRepository systemHealthSnapshotRepository) {
        this.persistenceConfig = persistenceConfig;
        this.alertHistoryRepository = alertHistoryRepository;
        this.mtmSnapshotRepository = mtmSnapshotRepository;
        this.webSocketEventRepository = webSocketEventRepository;
        this.systemHealthSnapshotRepository = systemHealthSnapshotRepository;
    }

    // ==================== NON-BLOCKING BUFFER METHODS ====================

    /**
     * Buffer alert for async persistence. Non-blocking.
     * @return true if buffered, false if dropped (queue full or circuit open)
     */
    public boolean bufferAlert(AlertHistoryEntity alert) {
        if (!canPersist()) {
            droppedCount.incrementAndGet();
            return false;
        }

        boolean offered = alertBuffer.offer(alert);
        if (offered) {
            bufferedCount.incrementAndGet();
        } else {
            droppedCount.incrementAndGet();
            log.trace("Alert buffer full, dropping alert: {}", alert.getAlertType());
        }
        return offered;
    }

    /**
     * Buffer MTM snapshot with rate limiting. Non-blocking.
     * @return true if buffered, false if rate-limited or dropped
     */
    public boolean bufferMTMSnapshot(MTMSnapshotEntity snapshot) {
        if (!canPersist()) {
            droppedCount.incrementAndGet();
            return false;
        }

        // Rate limiting: only allow one MTM snapshot per interval
        long now = System.currentTimeMillis();
        long lastTime = lastMtmSnapshotTime.get();
        if (now - lastTime < MTM_MIN_INTERVAL_MS) {
            // Rate limited - silently skip (not an error condition for HFT)
            return false;
        }

        if (!lastMtmSnapshotTime.compareAndSet(lastTime, now)) {
            // Another thread just updated - skip this one
            return false;
        }

        boolean offered = mtmBuffer.offer(snapshot);
        if (offered) {
            bufferedCount.incrementAndGet();
        } else {
            droppedCount.incrementAndGet();
        }
        return offered;
    }

    /**
     * Buffer WebSocket event. Non-blocking.
     */
    public boolean bufferWebSocketEvent(WebSocketEventEntity event) {
        if (!canPersist()) {
            droppedCount.incrementAndGet();
            return false;
        }

        boolean offered = wsEventBuffer.offer(event);
        if (offered) {
            bufferedCount.incrementAndGet();
        } else {
            droppedCount.incrementAndGet();
        }
        return offered;
    }

    /**
     * Buffer system health snapshot. Non-blocking.
     */
    public boolean bufferHealthSnapshot(SystemHealthSnapshotEntity snapshot) {
        if (!canPersist()) {
            droppedCount.incrementAndGet();
            return false;
        }

        boolean offered = healthBuffer.offer(snapshot);
        if (offered) {
            bufferedCount.incrementAndGet();
        } else {
            droppedCount.incrementAndGet();
        }
        return offered;
    }

    // ==================== SCHEDULED FLUSH ====================

    /**
     * Flush all buffers periodically.
     * Runs every 5 seconds to batch writes efficiently.
     */
    @Scheduled(fixedRate = 5000)
    public void flushBuffers() {
        if (!persistenceConfig.isEnabled()) {
            return;
        }

        // Check circuit breaker reset
        checkCircuitBreaker();

        if (circuitOpen.get()) {
            log.trace("Circuit breaker open, skipping flush");
            return;
        }

        try {
            int totalFlushed = 0;
            totalFlushed += flushAlerts();
            totalFlushed += flushMTMSnapshots();
            totalFlushed += flushWebSocketEvents();
            totalFlushed += flushHealthSnapshots();

            if (totalFlushed > 0) {
                flushedCount.addAndGet(totalFlushed);
                log.debug("Flushed {} persistence records", totalFlushed);
            }

            // Reset failure counter on success
            consecutiveFailures.set(0);

        } catch (Exception e) {
            handleFlushFailure(e);
        }
    }

    @Transactional
    protected int flushAlerts() {
        List<AlertHistoryEntity> batch = new ArrayList<>(50);
        alertBuffer.drainTo(batch, 50);
        if (!batch.isEmpty()) {
            alertHistoryRepository.saveAll(batch);
        }
        return batch.size();
    }

    @Transactional
    protected int flushMTMSnapshots() {
        List<MTMSnapshotEntity> batch = new ArrayList<>(20);
        mtmBuffer.drainTo(batch, 20);
        if (!batch.isEmpty()) {
            mtmSnapshotRepository.saveAll(batch);
        }
        return batch.size();
    }

    @Transactional
    protected int flushWebSocketEvents() {
        List<WebSocketEventEntity> batch = new ArrayList<>(20);
        wsEventBuffer.drainTo(batch, 20);
        if (!batch.isEmpty()) {
            webSocketEventRepository.saveAll(batch);
        }
        return batch.size();
    }

    @Transactional
    protected int flushHealthSnapshots() {
        List<SystemHealthSnapshotEntity> batch = new ArrayList<>(10);
        healthBuffer.drainTo(batch, 10);
        if (!batch.isEmpty()) {
            systemHealthSnapshotRepository.saveAll(batch);
        }
        return batch.size();
    }

    // ==================== CIRCUIT BREAKER ====================

    private boolean canPersist() {
        if (!persistenceConfig.isEnabled()) {
            return false;
        }
        if (circuitOpen.get()) {
            return false;
        }
        return true;
    }

    private void checkCircuitBreaker() {
        if (circuitOpen.get()) {
            long elapsed = System.currentTimeMillis() - lastFailureTime.get();
            if (elapsed > CIRCUIT_RESET_MS) {
                log.info("Circuit breaker reset after {}ms", elapsed);
                circuitOpen.set(false);
                consecutiveFailures.set(0);
            }
        }
    }

    private void handleFlushFailure(Exception e) {
        int failures = consecutiveFailures.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());

        log.error("Persistence flush failed (attempt {}): {}", failures, e.getMessage());

        if (failures >= CIRCUIT_BREAKER_THRESHOLD) {
            circuitOpen.set(true);
            log.error("Circuit breaker OPENED after {} consecutive failures. " +
                      "Persistence operations will be dropped for {}ms",
                      failures, CIRCUIT_RESET_MS);
        }
    }

    // ==================== METRICS ====================

    public BufferMetrics getMetrics() {
        return BufferMetrics.builder()
                .alertQueueSize(alertBuffer.size())
                .mtmQueueSize(mtmBuffer.size())
                .wsEventQueueSize(wsEventBuffer.size())
                .healthQueueSize(healthBuffer.size())
                .bufferedTotal(bufferedCount.get())
                .flushedTotal(flushedCount.get())
                .droppedTotal(droppedCount.get())
                .circuitBreakerOpen(circuitOpen.get())
                .consecutiveFailures(consecutiveFailures.get())
                .build();
    }

    @lombok.Data
    @lombok.Builder
    public static class BufferMetrics {
        private int alertQueueSize;
        private int mtmQueueSize;
        private int wsEventQueueSize;
        private int healthQueueSize;
        private long bufferedTotal;
        private long flushedTotal;
        private long droppedTotal;
        private boolean circuitBreakerOpen;
        private int consecutiveFailures;
    }

    /**
     * Force flush all buffers immediately.
     * Used during shutdown or on-demand.
     */
    public void forceFlush() {
        log.info("Force flushing all persistence buffers...");
        try {
            int alerts = 0, mtm = 0, ws = 0, health = 0;

            // Flush in batches until empty
            while (!alertBuffer.isEmpty()) {
                alerts += flushAlerts();
            }
            while (!mtmBuffer.isEmpty()) {
                mtm += flushMTMSnapshots();
            }
            while (!wsEventBuffer.isEmpty()) {
                ws += flushWebSocketEvents();
            }
            while (!healthBuffer.isEmpty()) {
                health += flushHealthSnapshots();
            }

            log.info("Force flush completed: alerts={}, mtm={}, ws={}, health={}",
                    alerts, mtm, ws, health);
        } catch (Exception e) {
            log.error("Force flush failed: {}", e.getMessage());
        }
    }
}

