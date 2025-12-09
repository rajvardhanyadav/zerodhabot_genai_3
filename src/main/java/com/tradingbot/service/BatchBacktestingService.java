package com.tradingbot.service;

import com.tradingbot.dto.*;
import com.tradingbot.util.CurrentUserContext;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Service for running batch backtests
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BatchBacktestingService {

    private final BacktestingService backtestingService;
    private final ExecutorService batchExecutor = Executors.newCachedThreadPool(
            new CustomizableThreadFactory("batch-backtest-"));
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Execute multiple backtests in parallel or sequentially
     */
    public BatchBacktestResponse executeBatchBacktest(BatchBacktestRequest request) {
        String batchId = UUID.randomUUID().toString();
        String userId = CurrentUserContext.getRequiredUserId();
        LocalDateTime startTime = LocalDateTime.now(IST);

        log.info("Starting batch backtest {} with {} backtests for user {}",
                batchId, request.getBacktests().size(), userId);

        List<BacktestResponse> results = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        try {
            if (request.getRunSequentially() != null && request.getRunSequentially()) {
                // Run sequentially
                for (BacktestRequest backtestRequest : request.getBacktests()) {
                    try {
                        BacktestResponse response = executeBacktestWithUserContext(backtestRequest, userId);
                        results.add(response);
                        if ("COMPLETED".equals(response.getStatus())) {
                            successCount++;
                        } else {
                            failCount++;
                        }
                    } catch (Exception e) {
                        log.error("Backtest failed in batch {}: {}", batchId, e.getMessage(), e);
                        failCount++;
                        results.add(createFailedBacktestResponse(backtestRequest, e.getMessage()));
                    } catch (KiteException e) {
                        throw new RuntimeException(e);
                    }
                }
            } else {
                // Run in parallel
                List<Future<BacktestResponse>> futures = new ArrayList<>();

                for (BacktestRequest backtestRequest : request.getBacktests()) {
                    Future<BacktestResponse> future = batchExecutor.submit(
                            wrapCallableWithUserContext(userId, () -> {
                                try {
                                    return backtestingService.executeBacktest(backtestRequest);
                                } catch (Exception e) {
                                    log.error("Backtest failed: {}", e.getMessage(), e);
                                    return createFailedBacktestResponse(backtestRequest, e.getMessage());
                                } catch (KiteException e) {
                                    throw new RuntimeException(e);
                                }
                            }));
                    futures.add(future);
                }

                // Collect results
                for (Future<BacktestResponse> future : futures) {
                    try {
                        BacktestResponse response = future.get();
                        results.add(response);
                        if ("COMPLETED".equals(response.getStatus())) {
                            successCount++;
                        } else {
                            failCount++;
                        }
                    } catch (Exception e) {
                        log.error("Failed to get backtest result: {}", e.getMessage(), e);
                        failCount++;
                    }
                }
            }

        } catch (Exception e) {
            log.error("Batch backtest {} failed: {}", batchId, e.getMessage(), e);
        }

        LocalDateTime endTime = LocalDateTime.now(IST);
        long totalDurationMs = Duration.between(startTime, endTime).toMillis();

        // Calculate aggregate statistics
        BatchBacktestResponse.AggregateStatistics aggregateStats = calculateAggregateStatistics(results);

        return BatchBacktestResponse.builder()
                .batchId(batchId)
                .startTime(startTime)
                .endTime(endTime)
                .totalDurationMs(totalDurationMs)
                .totalBacktests(request.getBacktests().size())
                .successfulBacktests(successCount)
                .failedBacktests(failCount)
                .results(results)
                .aggregateStatistics(aggregateStats)
                .build();
    }

    /**
     * Execute a single backtest with user context
     */
    private BacktestResponse executeBacktestWithUserContext(BacktestRequest request, String userId)
            throws KiteException, IOException {
        String previousUser = CurrentUserContext.getUserId();
        try {
            CurrentUserContext.setUserId(userId);
            return backtestingService.executeBacktest(request);
        } finally {
            if (previousUser != null) {
                CurrentUserContext.setUserId(previousUser);
            } else {
                CurrentUserContext.clear();
            }
        }
    }

    /**
     * Wrap a callable with user context
     */
    private <T> Callable<T> wrapCallableWithUserContext(String userId, Callable<T> task) {
        return () -> {
            String previous = CurrentUserContext.getUserId();
            try {
                if (userId != null && !userId.isBlank()) {
                    CurrentUserContext.setUserId(userId);
                } else {
                    CurrentUserContext.clear();
                }
                return task.call();
            } finally {
                restoreUserContext(previous);
            }
        };
    }

    /**
     * Restore user context
     */
    private void restoreUserContext(String previousUserId) {
        if (previousUserId == null || previousUserId.isBlank()) {
            CurrentUserContext.clear();
        } else {
            CurrentUserContext.setUserId(previousUserId);
        }
    }

    /**
     * Create a failed backtest response
     */
    private BacktestResponse createFailedBacktestResponse(BacktestRequest request, String errorMessage) {
        LocalDateTime now = LocalDateTime.now(IST);
        return BacktestResponse.builder()
                .backtestId(UUID.randomUUID().toString())
                .strategyType(request.getStrategyType().name())
                .instrumentType(request.getInstrumentType())
                .backtestDate(request.getBacktestDate())
                .startTime(now)
                .endTime(now)
                .durationMs(0L)
                .status("FAILED")
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * Calculate aggregate statistics from all backtest results
     */
    private BatchBacktestResponse.AggregateStatistics calculateAggregateStatistics(List<BacktestResponse> results) {
        int totalWins = 0;
        int totalLosses = 0;
        double totalNetPnL = 0.0;
        double totalReturnPercentage = 0.0;
        Double bestReturn = null;
        Double worstReturn = null;
        long totalHoldingDurationMs = 0;
        int validResults = 0;

        for (BacktestResponse response : results) {
            if ("COMPLETED".equals(response.getStatus()) && response.getPerformanceMetrics() != null) {
                BacktestResponse.PerformanceMetrics metrics = response.getPerformanceMetrics();

                double netPnL = metrics.getNetProfitLoss() != null ? metrics.getNetProfitLoss() : 0.0;
                double returnPct = metrics.getReturnPercentage() != null ? metrics.getReturnPercentage() : 0.0;

                totalNetPnL += netPnL;
                totalReturnPercentage += returnPct;

                if (netPnL > 0) {
                    totalWins++;
                } else if (netPnL < 0) {
                    totalLosses++;
                }

                if (bestReturn == null || returnPct > bestReturn) {
                    bestReturn = returnPct;
                }

                if (worstReturn == null || returnPct < worstReturn) {
                    worstReturn = returnPct;
                }

                if (metrics.getHoldingDurationMs() != null) {
                    totalHoldingDurationMs += metrics.getHoldingDurationMs();
                }

                validResults++;
            }
        }

        double averageNetPnL = validResults > 0 ? totalNetPnL / validResults : 0.0;
        double averageReturnPercentage = validResults > 0 ? totalReturnPercentage / validResults : 0.0;
        double winRate = (totalWins + totalLosses) > 0
                ? (totalWins * 100.0) / (totalWins + totalLosses)
                : 0.0;
        long averageHoldingDurationMs = validResults > 0 ? totalHoldingDurationMs / validResults : 0;

        return BatchBacktestResponse.AggregateStatistics.builder()
                .totalNetPnL(totalNetPnL)
                .averageNetPnL(averageNetPnL)
                .totalReturnPercentage(totalReturnPercentage)
                .averageReturnPercentage(averageReturnPercentage)
                .totalWins(totalWins)
                .totalLosses(totalLosses)
                .winRate(winRate)
                .bestReturn(bestReturn)
                .worstReturn(worstReturn)
                .averageHoldingDurationMs((double) averageHoldingDurationMs)
                .averageHoldingDurationFormatted(formatDuration(averageHoldingDurationMs))
                .build();
    }

    /**
     * Format duration in human-readable format
     */
    private String formatDuration(long durationMs) {
        Duration duration = Duration.ofMillis(durationMs);
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();

        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }
}

