package com.tradingbot.service.persistence;

import com.tradingbot.config.PersistenceConfig;
import com.tradingbot.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Service for cleaning up old trading data based on retention policies.
 * Runs as a scheduled job to maintain database size and performance.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "persistence.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class DataCleanupService {

    private final PersistenceConfig persistenceConfig;
    private final TradeRepository tradeRepository;
    private final StrategyExecutionRepository strategyExecutionRepository;
    private final DeltaSnapshotRepository deltaSnapshotRepository;
    private final PositionSnapshotRepository positionSnapshotRepository;
    private final OrderTimingRepository orderTimingRepository;
    private final DailyPnLSummaryRepository dailyPnLSummaryRepository;

    /**
     * Scheduled cleanup job.
     * Runs based on the cron expression in configuration.
     */
    @Scheduled(cron = "${persistence.cleanup.cron:0 0 2 * * ?}")
    @Transactional
    public void performScheduledCleanup() {
        log.info("Starting scheduled data cleanup...");

        try {
            CleanupResult result = cleanupOldData();
            log.info("Data cleanup completed: {}", result);
        } catch (Exception e) {
            log.error("Data cleanup failed", e);
        }
    }

    /**
     * Perform cleanup of all old data based on retention policies.
     */
    @Transactional
    public CleanupResult cleanupOldData() {
        PersistenceConfig.RetentionConfig retention = persistenceConfig.getRetention();
        CleanupResult result = new CleanupResult();

        // Cleanup trades
        LocalDate tradeCutoff = LocalDate.now().minusDays(retention.getTradesDays());
        log.debug("Cleaning up trades older than {}", tradeCutoff);
        tradeRepository.deleteByTradingDateBefore(tradeCutoff);

        // Cleanup strategy executions
        LocalDateTime strategyCutoff = LocalDateTime.now().minusDays(retention.getTradesDays());
        log.debug("Cleaning up strategy executions older than {}", strategyCutoff);
        strategyExecutionRepository.deleteByCreatedAtBefore(strategyCutoff);

        // Cleanup delta snapshots
        LocalDateTime deltaCutoff = LocalDateTime.now().minusDays(retention.getDeltaSnapshotsDays());
        log.debug("Cleaning up delta snapshots older than {}", deltaCutoff);
        deltaSnapshotRepository.deleteBySnapshotTimestampBefore(deltaCutoff);

        // Cleanup position snapshots
        LocalDate positionCutoff = LocalDate.now().minusDays(retention.getPositionSnapshotsDays());
        log.debug("Cleaning up position snapshots older than {}", positionCutoff);
        positionSnapshotRepository.deleteBySnapshotDateBefore(positionCutoff);

        // Cleanup order timing metrics
        LocalDateTime timingCutoff = LocalDateTime.now().minusDays(retention.getOrderTimingDays());
        log.debug("Cleaning up order timing data older than {}", timingCutoff);
        orderTimingRepository.deleteByOrderTimestampBefore(timingCutoff);

        // Cleanup daily summaries (keep same as trades)
        log.debug("Cleaning up daily summaries older than {}", tradeCutoff);
        dailyPnLSummaryRepository.deleteByTradingDateBefore(tradeCutoff);

        result.setSuccess(true);
        return result;
    }

    /**
     * Result of a cleanup operation
     */
    @lombok.Data
    public static class CleanupResult {
        private boolean success;
        private long tradesDeleted;
        private long strategyExecutionsDeleted;
        private long deltaSnapshotsDeleted;
        private long positionSnapshotsDeleted;
        private long orderTimingsDeleted;
        private long dailySummariesDeleted;
    }
}

