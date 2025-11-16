package com.tradingbot.service.strategy;

import com.tradingbot.config.StrategyConfig;
import com.tradingbot.dto.StrategyRequest;
import com.tradingbot.model.StrategyCompletionReason;
import com.tradingbot.model.StrategyExecution;
import com.tradingbot.model.StrategyStatus;
import com.tradingbot.model.StrategyType;
import com.tradingbot.service.StrategyService;
import com.tradingbot.service.UnifiedTradingService;
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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Schedules auto-restart of strategies at the start of the next 5-minute candle
 * when the current strategy closes due to target/stoploss being hit.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StrategyRestartScheduler {

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");

    private final StrategyConfig strategyConfig;
    @Lazy
    private final StrategyService strategyService;
    private final UnifiedTradingService unifiedTradingService;
    private final TaskScheduler taskScheduler;

    // Track scheduled restarts to avoid duplicates per executionId
    private final Map<String, ScheduledFuture<?>> scheduledRestarts = new ConcurrentHashMap<>();

    /**
     * Schedule an auto-restart for the given execution if conditions are met.
     */
    public void scheduleRestart(StrategyExecution execution) {
        if (execution == null) {
            return;
        }

        if (!strategyConfig.isAutoRestartEnabled()) {
            log.debug("Auto-restart disabled in config; skipping for execution {}", execution.getExecutionId());
            return;
        }

        boolean isPaper = unifiedTradingService.isPaperTradingEnabled();
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
        Duration delay = CandleUtils.durationUntilNextFiveMinuteCandle(now);
        ZonedDateTime nextCandle = now.plus(delay);

        String tradingMode = isPaper ? StrategyConstants.TRADING_MODE_PAPER : StrategyConstants.TRADING_MODE_LIVE;
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

        ScheduledFuture<?> future = taskScheduler.schedule(task, Date.from(nextCandle.toInstant()));
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
        request.setStrategyType(StrategyType.ATM_STRADDLE);
        request.setInstrumentType(execution.getInstrumentType());
        request.setExpiry(execution.getExpiry());
        // Other fields (like quantity, SL/target) will rely on defaults or client-provided values or config defaults
        return request;
    }
}
