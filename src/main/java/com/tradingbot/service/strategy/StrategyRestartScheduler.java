package com.tradingbot.service.strategy;

import com.tradingbot.config.StrategyConfig;
import com.tradingbot.dto.StrategyRequest;
import com.tradingbot.model.StrategyCompletionReason;
import com.tradingbot.model.StrategyExecution;
import com.tradingbot.model.StrategyStatus;
import com.tradingbot.model.StrategyType;
import com.tradingbot.service.BotStatusService;
import com.tradingbot.service.StrategyService;
import com.tradingbot.util.CurrentUserContext;
import com.tradingbot.util.StrategyConstants;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Schedules auto-restart of strategies based on neutral market detection.
 *
 * <p>When a strategy completes (target/SL hit), instead of scheduling at the next 5-minute
 * candle boundary, this scheduler starts an asynchronous polling loop that:
 * <ol>
 *   <li>Checks {@code NeutralMarketDetectorService.isMarketNeutral()} every 30 seconds</li>
 *   <li>When neutral conditions are detected, waits a 1-minute buffer</li>
 *   <li>Then places a new ATM straddle</li>
 * </ol>
 *
 * <p>Listens to {@link StrategyService.StrategyCompletionEvent} and uses the execution's
 * stored trading mode.
 *
 * @since 4.2
 */
@Component
@Slf4j
public class StrategyRestartScheduler {

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 10);

    private final StrategyConfig strategyConfig;
    @Lazy
    private final StrategyService strategyService;
    private final TaskScheduler taskScheduler;
    private final DailyPnlGateService dailyPnlGateService;
    private final BotStatusService botStatusService;
    private final NeutralMarketDetectorService neutralMarketDetectorService;

    /** Clock for obtaining current time — overridable in tests. */
    private Clock clock = Clock.system(MARKET_ZONE);

    /** Track active polling loops to avoid duplicates per executionId. */
    private final Map<String, ScheduledFuture<?>> scheduledRestarts = new ConcurrentHashMap<>();

    /** Guard against multiple simultaneous strategy executions from the scheduler. */
    private final AtomicBoolean executionInProgress = new AtomicBoolean(false);

    public StrategyRestartScheduler(StrategyConfig strategyConfig,
                                    @Lazy StrategyService strategyService,
                                    TaskScheduler taskScheduler,
                                    DailyPnlGateService dailyPnlGateService,
                                    BotStatusService botStatusService,
                                    NeutralMarketDetectorService neutralMarketDetectorService) {
        this.strategyConfig = strategyConfig;
        this.strategyService = strategyService;
        this.taskScheduler = taskScheduler;
        this.dailyPnlGateService = dailyPnlGateService;
        this.botStatusService = botStatusService;
        this.neutralMarketDetectorService = neutralMarketDetectorService;
    }

    /**
     * Override the clock used for market-hours checks (package-private for testing).
     */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Listen to strategy completion events and schedule restart if conditions are met.
     */
    @EventListener
    public void onStrategyCompletion(StrategyService.StrategyCompletionEvent event) {
        StrategyExecution execution = event.execution();
        if (execution != null) {
            log.debug("Received completion event for execution {}: reason={}, mode={}",
                    execution.getExecutionId(), execution.getCompletionReason(), execution.getTradingMode());
            scheduleRestart(execution);
        }
    }

    /**
     * Check if the given time is within market trading hours (9:15 AM - 3:10 PM IST).
     */
    private boolean isWithinMarketHours(ZonedDateTime dateTime) {
        LocalTime time = dateTime.toLocalTime();
        return !time.isBefore(MARKET_OPEN) && !time.isAfter(MARKET_CLOSE);
    }

    /**
     * Schedule an auto-restart for the given execution if conditions are met.
     * Instead of waiting for the next 5-minute candle, starts a neutral market
     * polling loop that checks conditions every 30 seconds.
     */
    public void scheduleRestart(StrategyExecution execution) {
        if (execution == null) {
            return;
        }

        if (!strategyConfig.isAutoRestartEnabled()) {
            log.debug("Auto-restart disabled in config; skipping for execution {}", execution.getExecutionId());
            return;
        }

        // Use execution's stored trading mode instead of querying global state
        String tradingMode = execution.getTradingMode();
        boolean isPaper = StrategyConstants.TRADING_MODE_PAPER.equalsIgnoreCase(tradingMode);

        if (isPaper && !strategyConfig.isAutoRestartPaperEnabled()) {
            log.info("Auto-restart for PAPER mode disabled; skipping for execution {}", execution.getExecutionId());
            return;
        }
        if (!isPaper && !strategyConfig.isAutoRestartLiveEnabled()) {
            log.info("Auto-restart for LIVE mode disabled; skipping for execution {}", execution.getExecutionId());
            return;
        }

        if (execution.getStatus() != StrategyStatus.COMPLETED) {
            log.debug("Execution {} not COMPLETED (status={}), skipping auto-restart", execution.getExecutionId(), execution.getStatus());
            return;
        }

        StrategyCompletionReason reason = execution.getCompletionReason();
        if (reason != StrategyCompletionReason.TARGET_HIT && reason != StrategyCompletionReason.STOPLOSS_HIT) {
            log.debug("Execution {} completion reason {} not eligible for auto-restart", execution.getExecutionId(), reason);
            return;
        }

        int maxAutoRestarts = strategyConfig.getMaxAutoRestarts();
        if (maxAutoRestarts > 0 && execution.getAutoRestartCount() >= maxAutoRestarts) {
            log.info("Execution {} reached max auto restarts ({}), skipping schedule", execution.getExecutionId(), maxAutoRestarts);
            return;
        }

        // ==================== DAILY P&L GATE CHECK (SELL_ATM_STRADDLE only) ====================
        if (execution.getStrategyType() == StrategyType.SELL_ATM_STRADDLE) {
            java.util.Optional<StrategyCompletionReason> breachReason =
                    dailyPnlGateService.getBreachReason(execution.getUserId());
            if (breachReason.isPresent()) {
                java.math.BigDecimal cumulativePnl = dailyPnlGateService.getDailyPnl(execution.getUserId());
                log.warn("⛔ DAILY P&L GATE TRIGGERED for user={}: reason={}, cumulativePnl={}, execution={}. " +
                         "Auto-restart BLOCKED. Stopping bot.",
                         execution.getUserId(), breachReason.get(), cumulativePnl, execution.getExecutionId());

                botStatusService.markStopped();
                cancelScheduledRestartsForUser(execution.getUserId());
                return;
            }
        }

        String executionId = execution.getExecutionId();
        if (scheduledRestarts.containsKey(executionId)) {
            log.info("Auto-restart already scheduled for execution {}, ignoring duplicate request", executionId);
            return;
        }

        ZonedDateTime now = ZonedDateTime.now(clock);

        // Check if current time is within market hours
        if (!isWithinMarketHours(now)) {
            log.info("Current time {} is outside market hours ({} - {}), skipping auto-restart for execution {}",
                    now.toLocalTime(), MARKET_OPEN, MARKET_CLOSE, executionId);
            return;
        }

        log.info("Trade closed. Waiting for neutral market condition. [{}] execution={} (user={}), reason={}, " +
                 "strategyType={}, instrument={}, expiry={}",
                 tradingMode, executionId, execution.getUserId(), reason,
                 execution.getStrategyType(), execution.getInstrumentType(), execution.getExpiry());

        // Build a StrategyRequest from the previous execution for the restart
        StrategyRequest request = buildRestartRequestFromExecution(execution);

        // Start the neutral market polling loop
        startNeutralMarketPolling(execution, request);
    }

    /**
     * Start an async polling loop that checks neutral market conditions every
     * {@code strategy.neutral-market-poll-interval-ms} (default 30s).
     * When neutral is detected, waits {@code strategy.neutral-market-buffer-ms} (default 1 min)
     * then places a new ATM straddle.
     */
    private void startNeutralMarketPolling(StrategyExecution execution, StrategyRequest request) {
        String executionId = execution.getExecutionId();
        String tradingMode = execution.getTradingMode();
        String instrumentType = execution.getInstrumentType();
        long pollIntervalMs = strategyConfig.getNeutralMarketPollIntervalMs();

        // Schedule first poll after the poll interval
        Instant firstPollTime = clock.instant().plusMillis(pollIntervalMs);

        Runnable pollTask = new Runnable() {
            @Override
            public void run() {
                try {
                    // Check if market hours are still valid
                    ZonedDateTime now = ZonedDateTime.now(clock);
                    if (!isWithinMarketHours(now)) {
                        log.info("Market hours ended ({}) during neutral market polling for execution {}. Stopping poll.",
                                now.toLocalTime(), executionId);
                        scheduledRestarts.remove(executionId);
                        return;
                    }

                    // Check neutral market condition (uses internal cache — max 4 API calls per 30s)
                    boolean isNeutral = neutralMarketDetectorService.isMarketNeutral(instrumentType);

                    if (isNeutral) {
                        log.info("Neutral market detected. Buffer timer started. [{}] execution={}, instrument={}",
                                tradingMode, executionId, instrumentType);

                        // Remove the polling entry — we're moving to the buffer phase
                        scheduledRestarts.remove(executionId);

                        // Schedule the actual execution after the buffer period
                        scheduleBufferedExecution(execution, request);
                    } else {
                        log.debug("[{}] Market not neutral for {} — continuing poll. execution={}",
                                tradingMode, instrumentType, executionId);

                        // Re-schedule the next poll
                        rescheduleNextPoll(executionId, this);
                    }
                } catch (Exception e) {
                    log.error("Error during neutral market poll for execution {}: {}",
                            executionId, e.getMessage(), e);
                    // Re-schedule despite error to avoid silently stopping the loop
                    rescheduleNextPoll(executionId, this);
                }
            }
        };

        ScheduledFuture<?> future = taskScheduler.schedule(pollTask, firstPollTime);
        scheduledRestarts.put(executionId, future);
    }

    /**
     * Reschedule the next neutral market poll after the configured interval.
     */
    private void rescheduleNextPoll(String executionId, Runnable pollTask) {
        // Only reschedule if the executionId is still tracked (not cancelled externally)
        if (!scheduledRestarts.containsKey(executionId)) {
            log.debug("Polling for execution {} was cancelled externally, not rescheduling", executionId);
            return;
        }

        long pollIntervalMs = strategyConfig.getNeutralMarketPollIntervalMs();
        Instant nextPollTime = clock.instant().plusMillis(pollIntervalMs);

        ScheduledFuture<?> future = taskScheduler.schedule(pollTask, nextPollTime);
        scheduledRestarts.put(executionId, future);
    }

    /**
     * After neutral market is confirmed, wait a configurable buffer period
     * then execute the strategy restart.
     */
    private void scheduleBufferedExecution(StrategyExecution execution, StrategyRequest request) {
        String executionId = execution.getExecutionId();
        String tradingMode = execution.getTradingMode();
        long bufferMs = strategyConfig.getNeutralMarketBufferMs();

        Instant executeTime = clock.instant().plusMillis(bufferMs);

        log.info("[{}] Neutral market buffer: waiting {}ms before placing ATM straddle for execution {}",
                tradingMode, bufferMs, executionId);

        Runnable executeTask = () -> {
            // Guard against multiple simultaneous executions
            if (!executionInProgress.compareAndSet(false, true)) {
                log.warn("Another strategy execution is already in progress. " +
                         "Skipping restart for execution {}", executionId);
                return;
            }

            try {
                // Defensive check: verify market hours at execution time
                ZonedDateTime now = ZonedDateTime.now(clock);
                if (!isWithinMarketHours(now)) {
                    log.warn("Auto-restart triggered outside market hours ({} - {}) at {}, skipping execution {}",
                            MARKET_OPEN, MARKET_CLOSE, now.toLocalTime(), executionId);
                    return;
                }

                // Re-check that market is still neutral after the buffer
                boolean stillNeutral = neutralMarketDetectorService.isMarketNeutral(
                        execution.getInstrumentType());
                if (!stillNeutral) {
                    log.info("[{}] Market no longer neutral after buffer period for execution {}. " +
                             "Restarting polling loop.", tradingMode, executionId);
                    // Go back to polling
                    startNeutralMarketPolling(execution, request);
                    return;
                }

                // Preserve and propagate user context into the scheduler thread
                String previousUserId = CurrentUserContext.getUserId();
                try {
                    if (execution.getUserId() != null && !execution.getUserId().isBlank()) {
                        CurrentUserContext.setUserId(execution.getUserId());
                    }

                    String newExecutionId = UUID.randomUUID().toString();
                    log.info("Executing next ATM straddle after neutral condition confirmation. " +
                             "[{} MODE] execution={} (user={}), strategyType={}, instrument={}, expiry={}. New executionId={}",
                             tradingMode, executionId, execution.getUserId(),
                             execution.getStrategyType(), execution.getInstrumentType(),
                             execution.getExpiry(), newExecutionId);

                    strategyService.executeStrategy(request);

                    execution.setAutoRestartCount(execution.getAutoRestartCount() + 1);

                } catch (KiteException | java.io.IOException e) {
                    log.error("Failed to auto-restart strategy for execution {}: {}", executionId, e.getMessage(), e);
                } catch (Exception e) {
                    log.error("Unexpected error while auto-restarting strategy for execution {}: {}", executionId, e.getMessage(), e);
                } finally {
                    // Restore previous user context on this thread
                    if (previousUserId == null || previousUserId.isBlank()) {
                        CurrentUserContext.clear();
                    } else {
                        CurrentUserContext.setUserId(previousUserId);
                    }
                }
            } finally {
                executionInProgress.set(false);
            }
        };

        ScheduledFuture<?> future = taskScheduler.schedule(executeTask, executeTime);
        scheduledRestarts.put(executionId, future);
    }

    /**
     * Listener method that allows external callers to pass only an executionId.
     */
    public void scheduleRestart(String executionId) {
        StrategyExecution execution = strategyService.getStrategy(executionId);
        scheduleRestart(execution);
    }

    private StrategyRequest buildRestartRequestFromExecution(StrategyExecution execution) {
        StrategyRequest request = new StrategyRequest();
        request.setStrategyType(execution.getStrategyType());
        request.setInstrumentType(execution.getInstrumentType());
        request.setExpiry(execution.getExpiry());
        request.setTargetPoints(execution.getTargetPoints());
        request.setStopLossPoints(execution.getStopLossPoints());
        request.setSlTargetMode(execution.getSlTargetMode());
        request.setLots(execution.getLots());
        // Preserve premium-based exit parameters from original execution
        request.setTargetDecayPct(execution.getTargetDecayPct());
        request.setStopLossExpansionPct(execution.getStopLossExpansionPct());
        return request;
    }

    /**
     * Cancel a scheduled restart for a specific execution.
     *
     * @param executionId The execution ID whose restart should be cancelled
     * @return true if a restart was cancelled, false if no restart was scheduled
     */
    public boolean cancelScheduledRestart(String executionId) {
        ScheduledFuture<?> future = scheduledRestarts.remove(executionId);
        if (future != null) {
            boolean cancelled = future.cancel(false);
            log.info("Cancelled scheduled auto-restart for execution {}: {}", executionId, cancelled);
            return cancelled;
        }
        return false;
    }

    /**
     * Cancel all scheduled restarts for a specific user.
     *
     * @param userId The user ID whose scheduled restarts should be cancelled
     * @return count of cancelled restarts
     */
    public int cancelScheduledRestartsForUser(String userId) {
        if (userId == null || userId.isBlank()) {
            log.warn("Cannot cancel restarts for null/blank userId");
            return 0;
        }

        int cancelledCount = 0;

        for (String executionId : List.copyOf(scheduledRestarts.keySet())) {
            StrategyExecution execution = strategyService.getStrategyByIdInternal(executionId);
            if (execution != null && userId.equals(execution.getUserId())) {
                ScheduledFuture<?> future = scheduledRestarts.remove(executionId);
                if (future != null) {
                    boolean cancelled = future.cancel(false);
                    if (cancelled) {
                        cancelledCount++;
                        log.debug("Cancelled scheduled restart for execution {} (user={})", executionId, userId);
                    }
                }
            }
        }

        if (cancelledCount > 0) {
            log.info("Cancelled {} scheduled auto-restarts for user {}", cancelledCount, userId);
        }

        return cancelledCount;
    }

    /**
     * Cancel all scheduled restarts (admin/system function).
     *
     * @return count of cancelled restarts
     */
    public int cancelAllScheduledRestarts() {
        int cancelledCount = 0;
        for (Map.Entry<String, ScheduledFuture<?>> entry : scheduledRestarts.entrySet()) {
            if (entry.getValue().cancel(false)) {
                cancelledCount++;
                log.debug("Cancelled scheduled restart for execution {}", entry.getKey());
            }
        }
        scheduledRestarts.clear();

        if (cancelledCount > 0) {
            log.info("Cancelled {} scheduled auto-restarts (all users)", cancelledCount);
        }

        return cancelledCount;
    }

    /**
     * Get count of currently scheduled restarts.
     *
     * @return number of scheduled restarts
     */
    public int getScheduledRestartsCount() {
        return scheduledRestarts.size();
    }

    /**
     * Get count of scheduled restarts for a specific user.
     *
     * @param userId The user ID to check
     * @return number of scheduled restarts for this user
     */
    public int getScheduledRestartsCountForUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0;
        }

        int count = 0;
        for (String executionId : scheduledRestarts.keySet()) {
            StrategyExecution execution = strategyService.getStrategy(executionId);
            if (execution != null && userId.equals(execution.getUserId())) {
                count++;
            }
        }
        return count;
    }
}
