package com.tradingbot.service;

import com.tradingbot.dto.StrategyExecutionResponse;
import com.tradingbot.dto.StrategyRequest;
import com.tradingbot.service.strategy.monitoring.PositionMonitor;
import com.tradingbot.service.strategy.monitoring.WebSocketService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

import com.tradingbot.util.CurrentUserContext;

@Service
@RequiredArgsConstructor
@Slf4j
public class HistoricalReplayService {

    private final StrategyService strategyService;
    private final HistoricalDataService historicalDataService;
    private final WebSocketService webSocketService;
    private final UnifiedTradingService unifiedTradingService;

    @Value("${historical.replay.sleep-millis-per-second:2}")
    private long replaySleepMillisPerSecond;

    private final ExecutorService replayExecutor = Executors.newCachedThreadPool(new CustomizableThreadFactory("hist-replay-"));

    /**
     * Execute the given strategy request in paper mode and replay most recent day's historical data per-second.
     * Returns the normal StrategyExecutionResponse from strategy execution and starts replay asynchronously.
     */
    public StrategyExecutionResponse executeWithHistoricalReplay(StrategyRequest request) throws KiteException, IOException {
        log.info("Starting historical replay for strategy: {} {}", request.getStrategyType(), request.getInstrumentType());

        if (!unifiedTradingService.isPaperTradingEnabled()) {
            throw new IllegalStateException("Historical replay requires paper trading mode to be enabled. Please enable trading.paperTradingEnabled=true.");
        }

        String userId = CurrentUserContext.getRequiredUserId();
        // Temporarily disable live WebSocket subscription so strategy monitoring registration doesn't subscribe
        webSocketService.setLiveSubscriptionEnabled(false);
        try {
            StrategyExecutionResponse response = strategyService.executeStrategy(request);

            if (response == null || response.getExecutionId() == null) {
                log.error("Strategy execution did not return an execution id; cannot start replay");
                return response;
            }
            String executionId = response.getExecutionId();

            Optional<PositionMonitor> monitorOpt = webSocketService.getMonitor(executionId);
            if (monitorOpt.isEmpty()) {
                log.error("No monitor found for execution id {}. Historical replay cannot proceed.", executionId);
                return response;
            }
            PositionMonitor monitor = monitorOpt.get();

            // Compute most recent trading day window
            HistoricalDataService.DayRange dr = historicalDataService.mostRecentTradingDayWindow();
            log.info("Historical replay window: {} to {} ({} IST)", dr.start, dr.end, dr.localDate);

            // Prepare tokens
            List<PositionMonitor.LegMonitor> legs = monitor.getLegs();
            if (legs.isEmpty()) {
                log.warn("No legs present in monitor for execution {}", executionId);
                return response;
            }

            // Fetch historical second-wise prices for each leg token in parallel
            Map<Long, NavigableMap<Long, Double>> tokenToSecondPrices = new ConcurrentHashMap<>();
            List<Callable<Void>> tasks = new ArrayList<>();
            for (PositionMonitor.LegMonitor leg : legs) {
                long token = leg.getInstrumentToken();
                tasks.add(wrapCallableWithUserContext(userId, () -> {
                    try {
                        NavigableMap<Long, Double> map = historicalDataService.getSecondWisePricesForToken(token, dr.start, dr.end);
                        tokenToSecondPrices.put(token, map);
                    } catch (Exception e) {
                        log.error("Error fetching historical for token {}: {}", token, e.getMessage(), e);
                        tokenToSecondPrices.put(token, new TreeMap<>());
                    }
                    return null;
                }));
            }
            try {
                List<Future<Void>> futures = replayExecutor.invokeAll(tasks);
                for (Future<Void> f : futures) f.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.error("Historical fetch interrupted", ie);
            } catch (ExecutionException ee) {
                log.error("Historical fetch failed: {}", ee.getMessage(), ee);
            }

            // Start asynchronous replay
            startReplayAsync(executionId, tokenToSecondPrices, userId);
            return response;
        } finally {
            // Re-enable for rest of app; our monitor remains registered but unsubscribed
            webSocketService.setLiveSubscriptionEnabled(true);
        }
    }

    private void startReplayAsync(String executionId, Map<Long, NavigableMap<Long, Double>> tokenToSecondPrices, String userId) {
        replayExecutor.submit(wrapRunnableWithUserContext(userId, () -> {
            try {
                log.info("Starting historical tick replay for execution {}", executionId);
                // Merge all seconds across tokens
                NavigableSet<Long> allSeconds = new TreeSet<>();
                for (NavigableMap<Long, Double> m : tokenToSecondPrices.values()) allSeconds.addAll(m.keySet());

                // Throttle based on configuration
                final long sleepMillisPerSecond = Math.max(0L, replaySleepMillisPerSecond);

                for (Long sec : allSeconds) {
                    PositionMonitor monitor = webSocketService.getMonitor(executionId).orElse(null);
                    if (monitor == null || !monitor.isActive()) {
                        log.info("Monitor inactive or missing for {}. Ending replay.", executionId);
                        break;
                    }

                    Map<Long, Double> tickPrices = new HashMap<>();
                    for (Map.Entry<Long, NavigableMap<Long, Double>> e : tokenToSecondPrices.entrySet()) {
                        Map.Entry<Long, Double> floor = e.getValue().floorEntry(sec);
                        if (floor != null) {
                            tickPrices.put(e.getKey(), floor.getValue());
                        }
                    }
                    if (!tickPrices.isEmpty()) {
                        monitor.updateWithTokenPrices(tickPrices);
                    }

                    if (sleepMillisPerSecond > 0) {
                        try { Thread.sleep(sleepMillisPerSecond); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    }
                }

                log.info("Historical replay completed for execution {}", executionId);
            } catch (Exception e) {
                log.error("Error during historical replay for {}: {}", executionId, e.getMessage(), e);
            }
        }));
    }

    private Callable<Void> wrapCallableWithUserContext(String userId, Callable<Void> task) {
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

    private Runnable wrapRunnableWithUserContext(String userId, Runnable task) {
        return () -> {
            String previous = CurrentUserContext.getUserId();
            try {
                if (userId != null && !userId.isBlank()) {
                    CurrentUserContext.setUserId(userId);
                } else {
                    CurrentUserContext.clear();
                }
                task.run();
            } finally {
                restoreUserContext(previous);
            }
        };
    }

    private void restoreUserContext(String previousUserId) {
        if (previousUserId == null || previousUserId.isBlank()) {
            CurrentUserContext.clear();
        } else {
            CurrentUserContext.setUserId(previousUserId);
        }
    }
}
