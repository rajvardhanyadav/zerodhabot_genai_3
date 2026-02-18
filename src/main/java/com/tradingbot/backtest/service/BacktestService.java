package com.tradingbot.backtest.service;

import com.tradingbot.backtest.dto.BacktestRequest;
import com.tradingbot.backtest.dto.BacktestResult;
import com.tradingbot.backtest.engine.BacktestEngine;
import com.tradingbot.backtest.strategy.BacktestStrategy;
import com.tradingbot.backtest.strategy.BacktestStrategyFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service layer for backtesting operations.
 *
 * Provides:
 * - Synchronous single-day backtest execution
 * - Async backtest execution for longer operations
 * - Backtest result caching and retrieval
 * - Batch backtest support for multiple days
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BacktestService {

    private final BacktestEngine backtestEngine;
    private final BacktestStrategyFactory strategyFactory;

    // Cache for completed backtest results (keyed by backtestId)
    private final Map<String, BacktestResult> resultCache = new ConcurrentHashMap<>();

    // Track running backtests
    private final Map<String, BacktestResult.BacktestStatus> runningBacktests = new ConcurrentHashMap<>();

    /**
     * Execute a backtest synchronously for a single day.
     *
     * @param request Backtest configuration
     * @return BacktestResult with detailed metrics
     */
    public BacktestResult runBacktest(BacktestRequest request) {
        validateRequest(request);

        log.info("Starting backtest: strategy={}, instrument={}, date={}",
                request.getStrategyType(), request.getInstrumentType(), request.getBacktestDate());

        BacktestStrategy strategy = strategyFactory.getStrategy(request.getStrategyType());
        BacktestResult result = backtestEngine.runBacktest(request, strategy);

        // Cache the result
        resultCache.put(result.getBacktestId(), result);

        log.info("Backtest completed: id={}, status={}, pnl={}",
                result.getBacktestId(), result.getStatus(), result.getTotalPnLAmount());

        return result;
    }

    /**
     * Execute a backtest asynchronously.
     * Returns immediately with a backtest ID for status polling.
     *
     * @param request Backtest configuration
     * @return CompletableFuture with the backtest result
     */
    @Async("backtestExecutor")
    public CompletableFuture<BacktestResult> runBacktestAsync(BacktestRequest request) {
        String trackingId = UUID.randomUUID().toString();
        runningBacktests.put(trackingId, BacktestResult.BacktestStatus.RUNNING);

        try {
            BacktestResult result = runBacktest(request);
            runningBacktests.remove(trackingId);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            runningBacktests.put(trackingId, BacktestResult.BacktestStatus.FAILED);
            throw e;
        }
    }

    /**
     * Run backtests for multiple days (batch mode).
     *
     * @param request Base request configuration
     * @param fromDate Start date (inclusive)
     * @param toDate End date (inclusive)
     * @return List of BacktestResult for each day
     */
    public List<BacktestResult> runBatchBacktest(BacktestRequest request,
                                                  LocalDate fromDate, LocalDate toDate) {
        List<BacktestResult> results = new ArrayList<>();
        LocalDate currentDate = fromDate;

        while (!currentDate.isAfter(toDate)) {
            BacktestRequest dayRequest = BacktestRequest.builder()
                    .backtestDate(currentDate)
                    .strategyType(request.getStrategyType())
                    .instrumentType(request.getInstrumentType())
                    .expiryDate(request.getExpiryDate())
                    .lots(request.getLots())
                    .stopLossPoints(request.getStopLossPoints())
                    .targetPoints(request.getTargetPoints())
                    .targetDecayPct(request.getTargetDecayPct())
                    .stopLossExpansionPct(request.getStopLossExpansionPct())
                    .slTargetMode(request.getSlTargetMode())
                    .candleInterval(request.getCandleInterval())
                    .fastForwardEnabled(request.isFastForwardEnabled())
                    .build();

            try {
                BacktestResult result = runBacktest(dayRequest);
                results.add(result);
            } catch (Exception e) {
                log.warn("Backtest failed for date {}: {}", currentDate, e.getMessage());
                // Continue with next date
            }

            currentDate = currentDate.plusDays(1);
        }

        return results;
    }

    /**
     * Get a cached backtest result by ID.
     *
     * @param backtestId The backtest ID
     * @return BacktestResult or null if not found
     */
    public BacktestResult getResult(String backtestId) {
        return resultCache.get(backtestId);
    }

    /**
     * Get the status of a running backtest.
     *
     * @param trackingId The tracking ID
     * @return Status or null if not found
     */
    public BacktestResult.BacktestStatus getStatus(String trackingId) {
        return runningBacktests.get(trackingId);
    }

    /**
     * Get all cached backtest results.
     */
    public List<BacktestResult> getAllResults() {
        return new ArrayList<>(resultCache.values());
    }

    /**
     * Clear the result cache.
     */
    public void clearCache() {
        resultCache.clear();
        log.info("Backtest result cache cleared");
    }

    /**
     * Validate backtest request parameters.
     */
    private void validateRequest(BacktestRequest request) {
        if (request.getBacktestDate() == null) {
            throw new IllegalArgumentException("Backtest date is required");
        }
        if (request.getBacktestDate().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Backtest date cannot be in the future");
        }
        if (request.getStrategyType() == null) {
            throw new IllegalArgumentException("Strategy type is required");
        }
        if (!strategyFactory.isSupported(request.getStrategyType())) {
            throw new IllegalArgumentException("Strategy type not supported for backtesting: "
                    + request.getStrategyType());
        }
        if (request.getInstrumentType() == null || request.getInstrumentType().isBlank()) {
            throw new IllegalArgumentException("Instrument type is required");
        }
        // Validate expiry date
        if (request.getExpiryDate() == null) {
            throw new IllegalArgumentException("Expiry date is required");
        }
        if (request.getExpiryDate().isBefore(request.getBacktestDate())) {
            throw new IllegalArgumentException("Expiry date cannot be before backtest date. " +
                    "Expiry: " + request.getExpiryDate() + ", Backtest: " + request.getBacktestDate());
        }
    }
}

