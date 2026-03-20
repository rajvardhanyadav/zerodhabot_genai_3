package com.tradingbot.service.strategy;

import com.tradingbot.config.StrategyConfig;
import com.tradingbot.dto.StrategyRequest;
import com.tradingbot.model.MarketStateEvent;
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
 * <p>When a strategy completes (target/SL hit), the execution is registered as a
 * pending restart. A separate {@link MarketStateUpdater} evaluates market neutrality
 * every 30 seconds and publishes {@link MarketStateEvent}s. This scheduler listens
 * for those events and triggers a buffered execution when the market is neutral.</p>
 *
 * <p>This event-driven design eliminates per-execution polling loops, reducing
 * thread blocking and redundant API calls when multiple executions await restart.</p>
 *
 * <p>Listens to {@link StrategyService.StrategyCompletionEvent} and uses the execution's
 * stored trading mode.
 *
 * @since 4.2
 * @see MarketStateUpdater
 * @see MarketStateEvent
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
    private final NeutralMarketDetectorServiceV2 neutralMarketDetectorService;

    /** Clock for obtaining current time — overridable in tests. */
    private Clock clock = Clock.system(MARKET_ZONE);

    /**
     * Pending restart registrations keyed by executionId.
     * Populated when a strategy completes; consumed when a NEUTRAL {@link MarketStateEvent} arrives.
     */
    private final Map<String, PendingRestart> pendingRestarts = new ConcurrentHashMap<>();

    /**
     * Track buffered execution futures so they can be cancelled externally.
     * Keyed by executionId.
     */
    private final Map<String, ScheduledFuture<?>> scheduledRestarts = new ConcurrentHashMap<>();

    /** Guard against multiple simultaneous strategy executions from the scheduler. */
    private final AtomicBoolean executionInProgress = new AtomicBoolean(false);

    /**
     * Holds the execution and its derived restart request while awaiting a neutral market event.
     */
    record PendingRestart(StrategyExecution execution, StrategyRequest request) {}

    public StrategyRestartScheduler(StrategyConfig strategyConfig,
                                    @Lazy StrategyService strategyService,
                                    TaskScheduler taskScheduler,
                                    DailyPnlGateService dailyPnlGateService,
                                    BotStatusService botStatusService,
                                    NeutralMarketDetectorServiceV2 neutralMarketDetectorService) {
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
     * Register an auto-restart for the given execution if conditions are met.
     * The execution is added to the pending restarts map and will be triggered
     * when a neutral {@link MarketStateEvent} arrives for the matching instrument.
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

        if (execution.getStatus() != StrategyStatus.COMPLETED && execution.getStatus() != StrategyStatus.SKIPPED) {
            log.debug("Execution {} not COMPLETED or SKIPPED (status={}), skipping auto-restart", execution.getExecutionId(), execution.getStatus());
            return;
        }

        // For SKIPPED executions (gate blocked), allow immediate restart registration
        // without requiring specific completion reasons (no positions were ever opened)
        if (execution.getStatus() == StrategyStatus.SKIPPED) {
            log.info("Execution {} was SKIPPED (gate blocked), registering for restart on next neutral market event",
                    execution.getExecutionId());
        } else {
            // For COMPLETED executions, only restart on TARGET or STOPLOSS
            StrategyCompletionReason reason = execution.getCompletionReason();
            if (reason != StrategyCompletionReason.TARGET_HIT && reason != StrategyCompletionReason.STOPLOSS_HIT) {
                log.debug("Execution {} completion reason {} not eligible for auto-restart", execution.getExecutionId(), reason);
                return;
            }
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
        if (pendingRestarts.containsKey(executionId) || scheduledRestarts.containsKey(executionId)) {
            log.info("Auto-restart already pending/scheduled for execution {}, ignoring duplicate request", executionId);
            return;
        }

        ZonedDateTime now = ZonedDateTime.now(clock);

        // Check if current time is within market hours
        if (!isWithinMarketHours(now)) {
            log.info("Current time {} is outside market hours ({} - {}), skipping auto-restart for execution {}",
                    now.toLocalTime(), MARKET_OPEN, MARKET_CLOSE, executionId);
            return;
        }

        log.info("Trade closed. Waiting for neutral market event. [{}] execution={} (user={}), status={}, reason={}, " +
                 "strategyType={}, instrument={}, expiry={}",
                 tradingMode, executionId, execution.getUserId(),
                 execution.getStatus(), execution.getCompletionReason(),
                 execution.getStrategyType(), execution.getInstrumentType(), execution.getExpiry());

        // Build a StrategyRequest from the previous execution for the restart
        StrategyRequest request = buildRestartRequestFromExecution(execution);

        // Register as pending — MarketStateUpdater will publish events that we listen to
        pendingRestarts.put(executionId, new PendingRestart(execution, request));
    }

    /**
     * React to market state events published by {@link MarketStateUpdater}.
     * When a NEUTRAL event arrives for an instrument that has pending restarts,
     * transitions those executions into the buffered execution phase.
     */
    @EventListener
    public void onMarketStateEvent(MarketStateEvent event) {
        if (!event.neutral()) {
            log.debug("Market not neutral for {} (score={}/{}), {} pending restart(s) waiting",
                    event.instrumentType(), event.score(), event.maxScore(), pendingRestarts.size());
            return;
        }

        // Check market hours at event time
        ZonedDateTime now = ZonedDateTime.now(clock);
        if (!isWithinMarketHours(now)) {
            log.info("MarketStateEvent received outside market hours ({}), ignoring", now.toLocalTime());
            return;
        }


        // Find pending restarts matching this instrument (snapshot keys to avoid concurrent modification)
        for (String executionId : List.copyOf(pendingRestarts.keySet())) {
            PendingRestart pending = pendingRestarts.get(executionId);
            if (pending == null) {
                continue; // already consumed by another thread
            }
            StrategyExecution execution = pending.execution();

            if (!event.instrumentType().equalsIgnoreCase(execution.getInstrumentType())) {
                continue;
            }

            // Transition from pending → buffered execution
            if (pendingRestarts.remove(executionId) == null) {
                continue; // another thread already consumed this entry
            }

            log.info("Neutral market detected via event. Buffer timer started. [{}] execution={}, instrument={}, score={}/{}",
                    execution.getTradingMode(), executionId, event.instrumentType(),
                    event.score(), event.maxScore());

            scheduleBufferedExecution(execution, pending.request());
        }
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
                             "Re-registering as pending restart.", tradingMode, executionId);
                    // Go back to waiting for the next neutral event
                    scheduledRestarts.remove(executionId);
                    pendingRestarts.put(executionId, new PendingRestart(execution, request));
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

                    var response = strategyService.executeStrategy(request);

                    // If the response was SKIPPED (gate blocked), re-register for next neutral event
                    if (response != null && StrategyStatus.SKIPPED.name().equalsIgnoreCase(response.getStatus())) {
                        log.info("[{}] Strategy restart resulted in SKIPPED for execution {}. " +
                                 "Reason: {}. Re-registering as pending restart.",
                                 tradingMode, executionId, response.getMessage());
                        scheduledRestarts.remove(executionId);
                        pendingRestarts.put(executionId, new PendingRestart(execution, request));
                    } else {
                        execution.setAutoRestartCount(execution.getAutoRestartCount() + 1);
                    }

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
        // Preserve hedge and order type from original execution
        request.setHedgeEnabled(execution.getHedgeEnabled());
        request.setOrderType(execution.getOrderType());
        return request;
    }

    /**
     * Cancel a scheduled restart for a specific execution.
     *
     * @param executionId The execution ID whose restart should be cancelled
     * @return true if a restart was cancelled, false if no restart was scheduled
     */
    public boolean cancelScheduledRestart(String executionId) {
        boolean removed = pendingRestarts.remove(executionId) != null;
        ScheduledFuture<?> future = scheduledRestarts.remove(executionId);
        if (future != null) {
            boolean cancelled = future.cancel(false);
            log.info("Cancelled scheduled auto-restart for execution {}: {}", executionId, cancelled);
            return cancelled;
        }
        if (removed) {
            log.info("Removed pending auto-restart for execution {}", executionId);
        }
        return removed;
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

        // Cancel pending restarts (awaiting neutral event)
        for (String executionId : List.copyOf(pendingRestarts.keySet())) {
            PendingRestart pending = pendingRestarts.get(executionId);
            if (pending != null && userId.equals(pending.execution().getUserId())) {
                pendingRestarts.remove(executionId);
                cancelledCount++;
                log.debug("Removed pending restart for execution {} (user={})", executionId, userId);
            }
        }

        // Cancel scheduled (buffered) restarts
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
            log.info("Cancelled {} auto-restarts (pending + scheduled) for user {}", cancelledCount, userId);
        }

        return cancelledCount;
    }

    /**
     * Cancel all scheduled restarts (admin/system function).
     *
     * @return count of cancelled restarts
     */
    public int cancelAllScheduledRestarts() {
        int cancelledCount = pendingRestarts.size();
        pendingRestarts.clear();

        for (Map.Entry<String, ScheduledFuture<?>> entry : scheduledRestarts.entrySet()) {
            if (entry.getValue().cancel(false)) {
                cancelledCount++;
                log.debug("Cancelled scheduled restart for execution {}", entry.getKey());
            }
        }
        scheduledRestarts.clear();

        if (cancelledCount > 0) {
            log.info("Cancelled {} auto-restarts (pending + scheduled, all users)", cancelledCount);
        }

        return cancelledCount;
    }

    /**
     * Get count of currently pending + scheduled restarts.
     *
     * @return number of pending and scheduled restarts
     */
    public int getScheduledRestartsCount() {
        return pendingRestarts.size() + scheduledRestarts.size();
    }

    /**
     * Get count of pending + scheduled restarts for a specific user.
     *
     * @param userId The user ID to check
     * @return number of pending and scheduled restarts for this user
     */
    public int getScheduledRestartsCountForUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0;
        }

        int count = 0;

        // Count pending restarts
        for (PendingRestart pending : pendingRestarts.values()) {
            if (userId.equals(pending.execution().getUserId())) {
                count++;
            }
        }

        // Count scheduled (buffered) restarts
        for (String executionId : scheduledRestarts.keySet()) {
            StrategyExecution execution = strategyService.getStrategy(executionId);
            if (execution != null && userId.equals(execution.getUserId())) {
                count++;
            }
        }
        return count;
    }
}
