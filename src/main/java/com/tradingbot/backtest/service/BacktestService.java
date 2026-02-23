package com.tradingbot.backtest.service;

import com.tradingbot.backtest.adapter.HistoricalCandleAdapter;
import com.tradingbot.backtest.adapter.HistoricalCandleAdapter.SimulatedCandle;
import com.tradingbot.backtest.config.BacktestConfig;
import com.tradingbot.backtest.dto.BacktestRequest;
import com.tradingbot.backtest.dto.BacktestResult;
import com.tradingbot.backtest.dto.BacktestResult.BacktestStatus;
import com.tradingbot.backtest.dto.BacktestTrade;
import com.tradingbot.backtest.engine.BacktestEngine;
import com.tradingbot.backtest.engine.BacktestException;
import com.tradingbot.backtest.engine.HistoricalDataFetcher;
import com.tradingbot.backtest.engine.InstrumentResolver;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Instrument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrates backtest execution: data fetching → simulation → result computation.
 * <p>
 * Fetches index candles and NFO instrument dump <b>once per backtest day</b>, then passes
 * them to the {@link BacktestEngine} which dynamically resolves ATM CE/PE instruments
 * at each entry point (initial + every auto-restart) using the spot price at that time.
 * <p>
 * Fully isolated from live/paper trading — creates its own PositionMonitorV2 instances
 * and never touches real order placement, WebSocket, or strategy state.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BacktestService {

    private final BacktestConfig backtestConfig;
    private final InstrumentResolver instrumentResolver;
    private final HistoricalDataFetcher historicalDataFetcher;

    /** In-memory result cache. */
    private final Map<String, BacktestResult> resultCache = new ConcurrentHashMap<>();

    /** Lazy-initialized async executor. */
    private volatile ExecutorService asyncExecutor;

    // ==================== SINGLE DAY BACKTEST ====================

    /**
     * Run a single-day backtest synchronously.
     * <p>
     * Fetches index (NIFTY/BANKNIFTY) candle data and NFO instruments once,
     * then passes them to the engine. The engine dynamically resolves ATM CE/PE
     * at each entry point using the spot price at that specific time.
     */
    public BacktestResult runSingleDay(BacktestRequest request) {
        if (!backtestConfig.isEnabled()) {
            throw new BacktestException(BacktestException.ErrorCode.BACKTEST_DISABLED,
                    "Backtest module is disabled in configuration");
        }

        String backtestId = UUID.randomUUID().toString();
        long startMs = System.currentTimeMillis();

        log.info("Starting backtest {}: date={}, strategy={}, instrument={}, expiry={}",
                backtestId, request.getBacktestDate(), request.getStrategyType(),
                request.getInstrumentType(), request.getExpiryDate());

        try {
            validateRequest(request);

            // Step 1: Fetch full-day index candles (for spot price lookup at any time)
            String indexToken = instrumentResolver.getIndexToken(request.getInstrumentType());
            String interval = request.getCandleInterval() != null
                    ? request.getCandleInterval() : backtestConfig.getDefaultCandleInterval();
            HistoricalData indexData = historicalDataFetcher.fetchDayCandles(
                    indexToken, request.getBacktestDate(), interval);
            // Use a dummy token (0) for index candles — only close price matters for spot lookup
            List<SimulatedCandle> indexCandles = HistoricalCandleAdapter.convert(indexData, 0L);

            if (indexCandles.isEmpty()) {
                throw new BacktestException(BacktestException.ErrorCode.DATA_FETCH_FAILED,
                        "No index candle data for " + request.getInstrumentType() + " on " + request.getBacktestDate());
            }
            log.info("Fetched {} index candles for spot price lookup", indexCandles.size());

            // Step 2: Fetch NFO instrument dump once (reused for every ATM resolution)
            List<Instrument> nfoInstruments = instrumentResolver.fetchNfoInstruments();
            log.info("Fetched {} NFO instruments", nfoInstruments.size());

            // Step 3: Determine lot size
            int lotSize = instrumentResolver.getDefaultLotSize(request.getInstrumentType());
            // Try to get actual lot size from NFO dump (first matching instrument)
            for (Instrument inst : nfoInstruments) {
                if (inst.name != null && inst.name.equalsIgnoreCase(request.getInstrumentType())
                        && inst.lot_size > 0) {
                    lotSize = inst.lot_size;
                    break;
                }
            }
            int quantity = request.getLots() * lotSize;

            // Step 4: Create engine and run simulation
            // The engine will dynamically resolve ATM CE/PE at each entry point
            // (initial entry + every auto-restart) using the spot price at that time.
            BacktestEngine engine = new BacktestEngine(
                    request, indexCandles, nfoInstruments,
                    instrumentResolver, historicalDataFetcher, quantity);

            List<BacktestTrade> trades = engine.runSimulation();

            // Step 5: Compute metrics and build result
            long durationMs = System.currentTimeMillis() - startMs;
            // Use first trade's strike for the result, or 0 if no trades
            double spotForResult = !indexCandles.isEmpty() ? indexCandles.get(0).close() : 0;
            double strikeForResult = !trades.isEmpty() ? trades.get(0).getStrikePrice() : 0;

            BacktestResult result = buildResult(backtestId, request, spotForResult, strikeForResult,
                    trades, durationMs);

            cacheResult(result);

            log.info("Backtest {} completed: {} trades, P&L={} pts, duration={}ms",
                    backtestId, trades.size(), String.format("%.2f", result.getTotalPnLPoints()), durationMs);

            return result;

        } catch (BacktestException e) {
            long durationMs = System.currentTimeMillis() - startMs;
            log.error("Backtest {} failed: {}", backtestId, e.getMessage());
            BacktestResult failedResult = BacktestResult.builder()
                    .backtestId(backtestId)
                    .backtestDate(request.getBacktestDate())
                    .strategyType(request.getStrategyType().name())
                    .instrumentType(request.getInstrumentType())
                    .status(BacktestStatus.FAILED)
                    .errorMessage(e.getMessage())
                    .trades(Collections.emptyList())
                    .executionDurationMs(durationMs)
                    .build();
            cacheResult(failedResult);
            return failedResult;

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            log.error("Unexpected error in backtest {}: {}", backtestId, e.getMessage(), e);
            BacktestResult failedResult = BacktestResult.builder()
                    .backtestId(backtestId)
                    .backtestDate(request.getBacktestDate())
                    .strategyType(request.getStrategyType() != null ? request.getStrategyType().name() : "UNKNOWN")
                    .instrumentType(request.getInstrumentType())
                    .status(BacktestStatus.FAILED)
                    .errorMessage("Unexpected error: " + e.getMessage())
                    .trades(Collections.emptyList())
                    .executionDurationMs(durationMs)
                    .build();
            cacheResult(failedResult);
            return failedResult;
        }
    }

    // ==================== BATCH BACKTEST ====================

    /**
     * Run backtests for each trading day in a date range.
     */
    public List<BacktestResult> runBatch(LocalDate fromDate, LocalDate toDate, BacktestRequest template) {
        if (!backtestConfig.isEnabled()) {
            throw new BacktestException(BacktestException.ErrorCode.BACKTEST_DISABLED,
                    "Backtest module is disabled");
        }

        log.info("Starting batch backtest: from={}, to={}", fromDate, toDate);
        List<BacktestResult> results = new ArrayList<>();

        LocalDate current = fromDate;
        while (!current.isAfter(toDate)) {
            // Skip weekends
            if (current.getDayOfWeek() == DayOfWeek.SATURDAY || current.getDayOfWeek() == DayOfWeek.SUNDAY) {
                current = current.plusDays(1);
                continue;
            }

            // Clone template with this date
            BacktestRequest dayRequest = BacktestRequest.builder()
                    .backtestDate(current)
                    .strategyType(template.getStrategyType())
                    .instrumentType(template.getInstrumentType())
                    .expiryDate(template.getExpiryDate())
                    .lots(template.getLots())
                    .slTargetMode(template.getSlTargetMode())
                    .stopLossPoints(template.getStopLossPoints())
                    .targetPoints(template.getTargetPoints())
                    .targetDecayPct(template.getTargetDecayPct())
                    .stopLossExpansionPct(template.getStopLossExpansionPct())
                    .startTime(template.getStartTime())
                    .endTime(template.getEndTime())
                    .autoSquareOffTime(template.getAutoSquareOffTime())
                    .candleInterval(template.getCandleInterval())
                    .autoRestartEnabled(template.isAutoRestartEnabled())
                    .maxAutoRestarts(template.getMaxAutoRestarts())
                    .trailingStopEnabled(template.isTrailingStopEnabled())
                    .trailingActivationPoints(template.getTrailingActivationPoints())
                    .trailingDistancePoints(template.getTrailingDistancePoints())
                    .build();

            results.add(runSingleDay(dayRequest));
            current = current.plusDays(1);
        }

        log.info("Batch backtest complete: {} days processed", results.size());
        return results;
    }

    // ==================== ASYNC BACKTEST ====================

    /**
     * Start a backtest asynchronously. Returns the backtestId immediately.
     */
    public String runAsync(BacktestRequest request) {
        if (!backtestConfig.isEnabled()) {
            throw new BacktestException(BacktestException.ErrorCode.BACKTEST_DISABLED,
                    "Backtest module is disabled");
        }

        String backtestId = UUID.randomUUID().toString();

        // Store a RUNNING placeholder
        BacktestResult placeholder = BacktestResult.builder()
                .backtestId(backtestId)
                .backtestDate(request.getBacktestDate())
                .strategyType(request.getStrategyType().name())
                .instrumentType(request.getInstrumentType())
                .status(BacktestStatus.RUNNING)
                .trades(Collections.emptyList())
                .build();
        cacheResult(placeholder);

        // Submit to async executor
        getAsyncExecutor().submit(() -> {
            try {
                BacktestResult result = runSingleDay(request);
                // Override the auto-generated ID with our pre-assigned one
                result.setBacktestId(backtestId);
                cacheResult(result);
            } catch (Exception e) {
                log.error("Async backtest {} failed: {}", backtestId, e.getMessage(), e);
                BacktestResult failed = BacktestResult.builder()
                        .backtestId(backtestId)
                        .backtestDate(request.getBacktestDate())
                        .strategyType(request.getStrategyType().name())
                        .instrumentType(request.getInstrumentType())
                        .status(BacktestStatus.FAILED)
                        .errorMessage(e.getMessage())
                        .trades(Collections.emptyList())
                        .build();
                cacheResult(failed);
            }
        });

        return backtestId;
    }

    // ==================== RESULT ACCESS ====================

    public BacktestResult getResult(String backtestId) {
        return resultCache.get(backtestId);
    }

    public Collection<BacktestResult> getAllResults() {
        return Collections.unmodifiableCollection(resultCache.values());
    }

    public void clearCache() {
        int size = resultCache.size();
        resultCache.clear();
        log.info("Backtest result cache cleared: {} entries removed", size);
    }

    // ==================== INTERNAL HELPERS ====================

    private void validateRequest(BacktestRequest request) {
        if (request.getBacktestDate() == null) {
            throw new BacktestException(BacktestException.ErrorCode.INVALID_DATE, "Backtest date is required");
        }
        if (request.getBacktestDate().isAfter(LocalDate.now())) {
            throw new BacktestException(BacktestException.ErrorCode.INVALID_DATE,
                    "Backtest date cannot be in the future: " + request.getBacktestDate());
        }
        DayOfWeek dow = request.getBacktestDate().getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            throw new BacktestException(BacktestException.ErrorCode.INVALID_DATE,
                    "Backtest date is a weekend: " + request.getBacktestDate() + " (" + dow + ")");
        }
    }

    private BacktestResult buildResult(String backtestId, BacktestRequest request,
                                        double spotPriceAtEntry, double initialAtmStrike,
                                        List<BacktestTrade> trades, long durationMs) {
        double totalPnlPts = 0;
        double totalPnlAmt = 0;
        int wins = 0, losses = 0;
        double totalWinAmt = 0, totalLossAmt = 0;
        double runningPnl = 0, peakPnl = 0, maxDrawdown = 0, maxProfit = 0;
        int restartCount = 0;

        for (BacktestTrade t : trades) {
            totalPnlPts += t.getPnlPoints();
            totalPnlAmt += t.getPnlAmount();

            if (t.getPnlAmount() >= 0) {
                wins++;
                totalWinAmt += t.getPnlAmount();
            } else {
                losses++;
                totalLossAmt += Math.abs(t.getPnlAmount());
            }

            if (t.isWasRestarted()) restartCount++;

            // Drawdown calculation
            runningPnl += t.getPnlAmount();
            if (runningPnl > peakPnl) peakPnl = runningPnl;
            double drawdown = peakPnl > 0 ? ((peakPnl - runningPnl) / peakPnl) * 100.0 : 0;
            if (drawdown > maxDrawdown) maxDrawdown = drawdown;
            if (runningPnl > maxProfit) maxProfit = runningPnl;
        }

        int total = trades.size();
        double winRate = total > 0 ? (wins * 100.0 / total) : 0;
        double avgWin = wins > 0 ? totalWinAmt / wins : 0;
        double avgLoss = losses > 0 ? totalLossAmt / losses : 0;
        double profitFactor = totalLossAmt > 0 ? totalWinAmt / totalLossAmt : (totalWinAmt > 0 ? Double.MAX_VALUE : 0);

        // Max profit as % of first trade's entry premium (if available)
        double maxProfitPct = 0;
        if (!trades.isEmpty() && trades.get(0).getCombinedEntryPremium() > 0) {
            maxProfitPct = (maxProfit / (trades.get(0).getCombinedEntryPremium() * trades.get(0).getQuantity())) * 100.0;
        }

        return BacktestResult.builder()
                .backtestId(backtestId)
                .backtestDate(request.getBacktestDate())
                .strategyType(request.getStrategyType().name())
                .instrumentType(request.getInstrumentType())
                .status(BacktestStatus.COMPLETED)
                .spotPriceAtEntry(spotPriceAtEntry)
                .atmStrike(initialAtmStrike)
                .trades(trades)
                .totalPnLPoints(totalPnlPts)
                .totalPnLAmount(totalPnlAmt)
                .totalTrades(total)
                .winningTrades(wins)
                .losingTrades(losses)
                .winRate(Math.round(winRate * 100.0) / 100.0)
                .maxDrawdownPct(Math.round(maxDrawdown * 100.0) / 100.0)
                .maxProfitPct(Math.round(maxProfitPct * 100.0) / 100.0)
                .avgWinAmount(Math.round(avgWin * 100.0) / 100.0)
                .avgLossAmount(Math.round(avgLoss * 100.0) / 100.0)
                .profitFactor(profitFactor == Double.MAX_VALUE ? 999.99 : Math.round(profitFactor * 100.0) / 100.0)
                .restartCount(restartCount)
                .executionDurationMs(durationMs)
                .build();
    }

    private void cacheResult(BacktestResult result) {
        // Simple eviction: remove oldest if over limit
        if (resultCache.size() >= backtestConfig.getMaxCacheSize()) {
            String oldestKey = resultCache.keySet().iterator().next();
            resultCache.remove(oldestKey);
        }
        resultCache.put(result.getBacktestId(), result);
    }

    private ExecutorService getAsyncExecutor() {
        if (asyncExecutor == null) {
            synchronized (this) {
                if (asyncExecutor == null) {
                    asyncExecutor = Executors.newFixedThreadPool(backtestConfig.getAsyncPoolSize(), r -> {
                        Thread t = new Thread(r, "backtest-async-" + System.currentTimeMillis());
                        t.setDaemon(true);
                        return t;
                    });
                }
            }
        }
        return asyncExecutor;
    }
}


