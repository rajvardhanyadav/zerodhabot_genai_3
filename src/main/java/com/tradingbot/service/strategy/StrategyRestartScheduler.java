package com.tradingbot.service.strategy;

import com.tradingbot.config.StrategyConfig;
import com.tradingbot.dto.StrategyRequest;
import com.tradingbot.model.StrategyCompletionReason;
import com.tradingbot.model.StrategyExecution;
import com.tradingbot.model.StrategyStatus;
import com.tradingbot.model.StrategyType;
import com.tradingbot.service.StrategyService;
import com.tradingbot.util.CandleUtils;
import com.tradingbot.util.CurrentUserContext;
import com.tradingbot.util.StrategyConstants;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Schedules auto-restart of strategies at the start of the next 5-minute candle
 * when the current strategy closes due to target/stoploss being hit.
 * Listens to StrategyCompletionEvent and uses execution's stored trading mode.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StrategyRestartScheduler {

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 10);

    private final StrategyConfig strategyConfig;
    @Lazy
    private final StrategyService strategyService;
    private final TaskScheduler taskScheduler;

    // Track scheduled restarts to avoid duplicates per executionId
    private final Map<String, ScheduledFuture<?>> scheduledRestarts = new ConcurrentHashMap<>();

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
     *
     * @param dateTime the timestamp to check
     * @return true if within market hours, false otherwise
     */
    private boolean isWithinMarketHours(ZonedDateTime dateTime) {
        LocalTime time = dateTime.toLocalTime();
        return !time.isBefore(MARKET_OPEN) && !time.isAfter(MARKET_CLOSE);
    }

    /**
     * Schedule an auto-restart for the given execution if conditions are met.
     * Uses execution's stored tradingMode to determine paper vs live behavior.
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

        String executionId = execution.getExecutionId();
        if (scheduledRestarts.containsKey(executionId)) {
            log.info("Auto-restart already scheduled for execution {}, ignoring duplicate request", executionId);
            return;
        }

        ZonedDateTime now = ZonedDateTime.now(MARKET_ZONE);

        // Check if current time is within market hours
        if (!isWithinMarketHours(now)) {
            log.info("Current time {} is outside market hours ({} - {}), skipping auto-restart for execution {}",
                    now.toLocalTime(), MARKET_OPEN, MARKET_CLOSE, executionId);
            return;
        }

        Duration delay = CandleUtils.durationUntilNextFiveMinuteCandle(now);
        ZonedDateTime nextCandle = now.plus(delay);

        // Validate that the next candle time is also within market hours
        if (!isWithinMarketHours(nextCandle)) {
            log.info("Next candle {} is outside market hours ({} - {}), skipping auto-restart for execution {}",
                    nextCandle.toLocalTime(), MARKET_OPEN, MARKET_CLOSE, executionId);
            return;
        }

        log.info("[{} MODE] Scheduling auto-restart for execution {} (user={}) at next 5m candle {} (delay={}s), reason={}, strategyType={}, instrument={}, expiry={}",
                 tradingMode,
                 executionId,
                 execution.getUserId(),
                 nextCandle,
                 delay.toSeconds(),
                 reason,
                 execution.getStrategyType(),
                 execution.getInstrumentType(),
                 execution.getExpiry());

        // Build a StrategyRequest skeleton from the previous execution info
        StrategyRequest request = buildRestartRequestFromExecution(execution);

        Runnable task = () -> {
            ScheduledFuture<?> future = scheduledRestarts.remove(executionId);
            if (future != null) {
                future.cancel(false);
            }

            // Defensive check: verify market hours at execution time
            ZonedDateTime executeTime = ZonedDateTime.now(MARKET_ZONE);
            if (!isWithinMarketHours(executeTime)) {
                log.warn("Auto-restart triggered outside market hours ({} - {}) at {}, skipping execution {}",
                        MARKET_OPEN, MARKET_CLOSE, executeTime.toLocalTime(), executionId);
                return;
            }

            // Preserve and propagate user context into the scheduler thread
            String previousUserId = CurrentUserContext.getUserId();
            try {
                if (execution.getUserId() != null && !execution.getUserId().isBlank()) {
                    CurrentUserContext.setUserId(execution.getUserId());
                }

                try {
                    String newExecutionId = UUID.randomUUID().toString();
                    log.info("[{} MODE] Triggering auto-restart for execution {} (user={}) on candle {} with strategyType={}, instrument={}, expiry={}. New executionId={}",
                             tradingMode,
                             executionId,
                             execution.getUserId(),
                             ZonedDateTime.now(MARKET_ZONE),
                             execution.getStrategyType(),
                             execution.getInstrumentType(),
                             execution.getExpiry(),
                             newExecutionId);

                    // Execute strategy as usual under the correct user context
                    strategyService.executeStrategy(request);

                    execution.setAutoRestartCount(execution.getAutoRestartCount() + 1);
                } catch (KiteException | java.io.IOException e) {
                    log.error("Failed to auto-restart strategy for execution {}: {}", executionId, e.getMessage(), e);
                } catch (Exception e) {
                    log.error("Unexpected error while auto-restarting strategy for execution {}: {}", executionId, e.getMessage(), e);
                }
            } finally {
                // Restore previous user context on this thread
                if (previousUserId == null || previousUserId.isBlank()) {
                    CurrentUserContext.clear();
                } else {
                    CurrentUserContext.setUserId(previousUserId);
                }
            }
        };

        ScheduledFuture<?> future = taskScheduler.schedule(task, nextCandle.toInstant());
        scheduledRestarts.put(executionId, future);
    }

    /**
     * Listener method that can be wired to application events if StrategyService publishes them in future.
     * For now, this overload allows external callers to pass only an executionId and have the scheduler
     * look up the StrategyExecution via StrategyService.
     */
    public void scheduleRestart(String executionId) {
        StrategyExecution execution = strategyService.getStrategy(executionId);
        scheduleRestart(execution);
    }

    private StrategyRequest buildRestartRequestFromExecution(StrategyExecution execution) {
        StrategyRequest request = new StrategyRequest();
        // Always restart as ATM_STRADDLE as per new requirement, regardless of original type
        request.setStrategyType(execution.getStrategyType());
        request.setInstrumentType(execution.getInstrumentType());
        request.setExpiry(execution.getExpiry());
        request.setTargetPoints(execution.getTargetPoints());
        request.setStopLossPoints(execution.getStopLossPoints());
        request.setLots(execution.getLots());
        // Other fields (like quantity, SL/target) will rely on defaults or client-provided values or config defaults
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
     * This is useful when stopping all strategies for a user.
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
        // Get all executions for this user from StrategyService and cancel their scheduled restarts
        List<StrategyExecution> userExecutions = strategyService.getActiveStrategies();

        for (StrategyExecution execution : userExecutions) {
            if (userId.equals(execution.getUserId())) {
                if (cancelScheduledRestart(execution.getExecutionId())) {
                    cancelledCount++;
                }
            }
        }

        // Also check completed executions that might have scheduled restarts
        // We need to iterate through all scheduled restarts and match by looking up the execution
        for (String executionId : List.copyOf(scheduledRestarts.keySet())) {
            StrategyExecution execution = strategyService.getStrategy(executionId);
            if (execution != null && userId.equals(execution.getUserId())) {
                if (cancelScheduledRestart(executionId)) {
                    cancelledCount++;
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
