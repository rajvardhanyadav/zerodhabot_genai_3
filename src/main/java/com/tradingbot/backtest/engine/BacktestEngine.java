package com.tradingbot.backtest.engine;

import com.tradingbot.backtest.adapter.HistoricalDataAdapter;
import com.tradingbot.backtest.dto.BacktestRequest;
import com.tradingbot.backtest.dto.BacktestResult;
import com.tradingbot.backtest.dto.CandleData;
import com.tradingbot.backtest.strategy.BacktestStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Core backtesting engine that simulates strategy execution over historical data.
 *
 * Key Features:
 * - Completely isolated from live trading logic
 * - Implements "Fast Forward" mechanism for 5-minute candle alignment on restarts
 * - Supports both point-based and percentage-based SL/target modes
 * - Uses BigDecimal for all monetary calculations (HFT precision)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BacktestEngine {

    private final HistoricalDataAdapter historicalDataAdapter;

    // Market timing constants (IST)
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final int CANDLE_INTERVAL_MINUTES = 5;

    /**
     * Execute a backtest for the given request and strategy.
     *
     * @param request The backtest configuration
     * @param strategy The strategy to backtest
     * @return BacktestResult with detailed metrics
     */
    public BacktestResult runBacktest(BacktestRequest request, BacktestStrategy strategy) {
        String backtestId = UUID.randomUUID().toString();
        LocalDateTime startTime = LocalDateTime.now();

        log.info("Starting backtest: id={}, date={}, strategy={}",
                backtestId, request.getBacktestDate(), strategy.getStrategyName());

        BacktestContext context = initializeContext(request, backtestId);

        try {
            // Validate backtest date
            if (!historicalDataAdapter.isDataAvailable(request.getBacktestDate())) {
                return buildFailedResult(backtestId, request, startTime,
                        "No market data available for date: " + request.getBacktestDate());
            }

            // Fetch historical candles for the index (spot)
            List<CandleData> indexCandles = historicalDataAdapter.fetchIndexCandles(
                    request.getInstrumentType(),
                    request.getBacktestDate(),
                    request.getCandleInterval()
            );

            if (indexCandles.isEmpty()) {
                return buildFailedResult(backtestId, request, startTime,
                        "No candle data found for " + request.getInstrumentType());
            }

            log.info("Fetched {} candles for {}", indexCandles.size(), request.getInstrumentType());

            // Initialize strategy
            strategy.initialize(request, context);

            // Process candles through the simulation
            processCandles(indexCandles, strategy, context, request);

            // Build and return result
            return buildSuccessResult(backtestId, request, context, startTime);

        } catch (Exception e) {
            log.error("Backtest failed: id={}, error={}", backtestId, e.getMessage(), e);
            return buildFailedResult(backtestId, request, startTime, e.getMessage());
        }
    }

    /**
     * Process candles through the backtest simulation.
     * Handles restart fast-forwarding and market close square-off.
     */
    private void processCandles(List<CandleData> candles, BacktestStrategy strategy,
                                 BacktestContext context, BacktestRequest request) {
        List<CandleData> processedCandles = new ArrayList<>();

        for (int i = 0; i < candles.size(); i++) {
            CandleData candle = candles.get(i);
            context.setCurrentTime(candle.getTimestamp());

            // Check if we need to fast-forward after a restart
            if (context.isRestartRequested() && request.isFastForwardEnabled()) {
                CandleData alignedCandle = fastForwardToNextCandleBoundary(candle, candles, i);
                if (alignedCandle != candle) {
                    log.debug("Fast-forwarded from {} to {} for restart",
                            candle.getTimestamp(), alignedCandle.getTimestamp());
                    i = candles.indexOf(alignedCandle);
                    candle = alignedCandle;
                    context.setCurrentTime(candle.getTimestamp());
                }
                context.clearRestartFlag();
                strategy.onRestart(candle, context);
            }

            // Process the candle
            processedCandles.add(candle);
            strategy.onCandle(candle, context, processedCandles);

            // Check for market close
            if (isMarketClose(candle.getTimestamp())) {
                log.debug("Market close detected at {}", candle.getTimestamp());
                strategy.onMarketClose(candle, context);
                break;
            }
        }
    }

    /**
     * Fast Forward Mechanism:
     * When a strategy restart is triggered, immediately align with the START
     * of the nearest 5-minute candle boundary rather than waiting.
     *
     * For example:
     * - If restart triggered at 10:23, fast-forward to 10:25 candle start
     * - If restart triggered at 10:25:30, stay at 10:25 candle (already aligned)
     */
    private CandleData fastForwardToNextCandleBoundary(CandleData currentCandle,
                                                        List<CandleData> allCandles, int currentIndex) {
        LocalDateTime currentTime = currentCandle.getTimestamp();
        int minute = currentTime.getMinute();

        // Check if already aligned to 5-minute boundary
        if (minute % CANDLE_INTERVAL_MINUTES == 0) {
            return currentCandle;
        }

        // Calculate next 5-minute boundary
        int nextBoundaryMinute = ((minute / CANDLE_INTERVAL_MINUTES) + 1) * CANDLE_INTERVAL_MINUTES;
        LocalDateTime nextBoundary = currentTime
                .withMinute(nextBoundaryMinute % 60)
                .withSecond(0)
                .withNano(0);

        if (nextBoundaryMinute >= 60) {
            nextBoundary = nextBoundary.plusHours(1).withMinute(nextBoundaryMinute - 60);
        }

        // Find the candle that matches this boundary
        for (int i = currentIndex; i < allCandles.size(); i++) {
            CandleData candidate = allCandles.get(i);
            if (!candidate.getTimestamp().isBefore(nextBoundary)) {
                return candidate;
            }
        }

        // If no future candle found, return current
        return currentCandle;
    }

    /**
     * Check if the given time represents market close.
     */
    private boolean isMarketClose(LocalDateTime time) {
        LocalTime localTime = time.toLocalTime();
        return !localTime.isBefore(MARKET_CLOSE);
    }

    /**
     * Initialize the backtest context with request parameters.
     */
    private BacktestContext initializeContext(BacktestRequest request, String backtestId) {
        BacktestContext context = new BacktestContext();
        context.setBacktestId(backtestId);
        context.setLots(request.getLots() != null ? request.getLots() : 1);
        context.setSlTargetMode(request.getSlTargetMode());

        // Set instrument and expiry information
        context.setInstrumentType(request.getInstrumentType());
        context.setExpiryDate(request.getExpiryDate());
        context.setBacktestDate(request.getBacktestDate());
        context.setExpiryForSymbol(request.getWeeklyExpiryForSymbol());

        // Set SL/Target parameters based on mode
        if ("percentage".equalsIgnoreCase(request.getSlTargetMode())) {
            context.setTargetDecayPct(request.getTargetDecayPct());
            context.setStopLossExpansionPct(request.getStopLossExpansionPct());
        } else {
            context.setStopLossPoints(request.getStopLossPoints());
            context.setTargetPoints(request.getTargetPoints());
        }

        return context;
    }

    /**
     * Build a successful backtest result from the context.
     */
    private BacktestResult buildSuccessResult(String backtestId, BacktestRequest request,
                                               BacktestContext context, LocalDateTime startTime) {
        LocalDateTime endTime = LocalDateTime.now();
        long durationMs = java.time.Duration.between(startTime, endTime).toMillis();

        int winningTrades = (int) context.getTrades().stream()
                .filter(t -> t.getPnlAmount().compareTo(BigDecimal.ZERO) > 0)
                .count();
        int losingTrades = (int) context.getTrades().stream()
                .filter(t -> t.getPnlAmount().compareTo(BigDecimal.ZERO) < 0)
                .count();
        int totalTrades = context.getTrades().size();

        BigDecimal winRate = totalTrades > 0
                ? BigDecimal.valueOf(winningTrades * 100.0 / totalTrades)
                        .setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal avgWin = calculateAverageWin(context);
        BigDecimal avgLoss = calculateAverageLoss(context);
        BigDecimal profitFactor = calculateProfitFactor(context);

        return BacktestResult.builder()
                .backtestId(backtestId)
                .backtestDate(request.getBacktestDate())
                .strategyType(request.getStrategyType().name())
                .instrumentType(request.getInstrumentType())
                .status(BacktestResult.BacktestStatus.COMPLETED)
                .trades(context.getTrades())
                .totalPnLPoints(context.getTotalPnLPoints())
                .totalPnLAmount(context.getTotalPnLAmount())
                .totalCharges(context.getTotalCharges())
                .totalTrades(totalTrades)
                .winningTrades(winningTrades)
                .losingTrades(losingTrades)
                .winRate(winRate)
                .maxDrawdownPct(context.getMaxDrawdownPct())
                .maxProfitPct(calculateMaxProfit(context))
                .avgWinAmount(avgWin)
                .avgLossAmount(avgLoss)
                .profitFactor(profitFactor)
                .restartCount(context.getRestartCount())
                .executionStartTime(startTime)
                .executionEndTime(endTime)
                .executionDurationMs(durationMs)
                .build();
    }

    /**
     * Build a failed backtest result.
     */
    private BacktestResult buildFailedResult(String backtestId, BacktestRequest request,
                                              LocalDateTime startTime, String errorMessage) {
        LocalDateTime endTime = LocalDateTime.now();
        return BacktestResult.builder()
                .backtestId(backtestId)
                .backtestDate(request.getBacktestDate())
                .strategyType(request.getStrategyType().name())
                .instrumentType(request.getInstrumentType())
                .status(BacktestResult.BacktestStatus.FAILED)
                .errorMessage(errorMessage)
                .executionStartTime(startTime)
                .executionEndTime(endTime)
                .executionDurationMs(java.time.Duration.between(startTime, endTime).toMillis())
                .build();
    }

    private BigDecimal calculateAverageWin(BacktestContext context) {
        return context.getTrades().stream()
                .filter(t -> t.getPnlAmount().compareTo(BigDecimal.ZERO) > 0)
                .map(BacktestResult.BacktestTrade::getPnlAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(Math.max(1, context.getTrades().stream()
                        .filter(t -> t.getPnlAmount().compareTo(BigDecimal.ZERO) > 0)
                        .count())), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateAverageLoss(BacktestContext context) {
        return context.getTrades().stream()
                .filter(t -> t.getPnlAmount().compareTo(BigDecimal.ZERO) < 0)
                .map(t -> t.getPnlAmount().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(Math.max(1, context.getTrades().stream()
                        .filter(t -> t.getPnlAmount().compareTo(BigDecimal.ZERO) < 0)
                        .count())), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateProfitFactor(BacktestContext context) {
        BigDecimal totalProfit = context.getTrades().stream()
                .filter(t -> t.getPnlAmount().compareTo(BigDecimal.ZERO) > 0)
                .map(BacktestResult.BacktestTrade::getPnlAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalLoss = context.getTrades().stream()
                .filter(t -> t.getPnlAmount().compareTo(BigDecimal.ZERO) < 0)
                .map(t -> t.getPnlAmount().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalLoss.compareTo(BigDecimal.ZERO) == 0) {
            return totalProfit.compareTo(BigDecimal.ZERO) > 0
                    ? BigDecimal.valueOf(999.99) // Infinite profit factor
                    : BigDecimal.ZERO;
        }

        return totalProfit.divide(totalLoss, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateMaxProfit(BacktestContext context) {
        return context.getPeakEquity().compareTo(BigDecimal.ZERO) > 0
                ? context.getPeakEquity()
                : BigDecimal.ZERO;
    }
}

