package com.tradingbot.service.strategy;

import com.tradingbot.config.StrategyConfig;
import com.tradingbot.model.StrategyCompletionReason;
import com.tradingbot.model.StrategyExecution;
import com.tradingbot.model.StrategyStatus;
import com.tradingbot.model.StrategyType;
import com.tradingbot.service.BotStatusService;
import com.tradingbot.service.StrategyService;
import com.tradingbot.util.StrategyConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.scheduling.TaskScheduler;

import java.math.BigDecimal;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Test that StrategyRestartScheduler correctly schedules restarts for paper and live modes
 * based on the execution's stored trading mode.
 */
class StrategyRestartSchedulerTest {

    @Mock
    private StrategyConfig strategyConfig;

    @Mock
    private StrategyService strategyService;

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private DailyPnlGateService dailyPnlGateService;

    @Mock
    private BotStatusService botStatusService;

    private StrategyRestartScheduler scheduler;

    @BeforeEach
    void setUp() {
        try (AutoCloseable mocks = MockitoAnnotations.openMocks(this)) {
            scheduler = new StrategyRestartScheduler(strategyConfig, strategyService, taskScheduler,
                    dailyPnlGateService, botStatusService);
            // Default: no daily P&L gate breach
            when(dailyPnlGateService.getBreachReason(any())).thenReturn(Optional.empty());
            when(dailyPnlGateService.getDailyPnl(any())).thenReturn(BigDecimal.ZERO);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize mocks", e);
        }
    }

    @Test
    void testPaperModeCompletionSchedulesRestartWhenEnabled() {
        // Given: Auto-restart enabled globally and for paper mode
        when(strategyConfig.isAutoRestartEnabled()).thenReturn(true);
        when(strategyConfig.isAutoRestartPaperEnabled()).thenReturn(true);
        when(strategyConfig.getMaxAutoRestarts()).thenReturn(10);

        // Paper execution that hit target
        StrategyExecution execution = createExecution("exec-1", "user-1", StrategyConstants.TRADING_MODE_PAPER);
        execution.setStatus(StrategyStatus.COMPLETED);
        execution.setCompletionReason(StrategyCompletionReason.TARGET_HIT);

        // When: Event listener is invoked
        StrategyService.StrategyCompletionEvent event = new StrategyService.StrategyCompletionEvent(this, execution);
        scheduler.onStrategyCompletion(event);

        // Then: Scheduler should schedule a task
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(java.time.Instant.class));
    }

    @Test
    void testPaperModeCompletionDoesNotScheduleWhenDisabled() {
        // Given: Auto-restart enabled globally but disabled for paper mode
        when(strategyConfig.isAutoRestartEnabled()).thenReturn(true);
        when(strategyConfig.isAutoRestartPaperEnabled()).thenReturn(false);

        // Paper execution that hit stoploss
        StrategyExecution execution = createExecution("exec-2", "user-2", StrategyConstants.TRADING_MODE_PAPER);
        execution.setStatus(StrategyStatus.COMPLETED);
        execution.setCompletionReason(StrategyCompletionReason.STOPLOSS_HIT);

        // When: Event listener is invoked
        StrategyService.StrategyCompletionEvent event = new StrategyService.StrategyCompletionEvent(this, execution);
        scheduler.onStrategyCompletion(event);

        // Then: Scheduler should NOT schedule a task
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(java.time.Instant.class));
    }

    @Test
    void testLiveModeCompletionSchedulesRestartWhenEnabled() {
        // Given: Auto-restart enabled globally and for live mode
        when(strategyConfig.isAutoRestartEnabled()).thenReturn(true);
        when(strategyConfig.isAutoRestartLiveEnabled()).thenReturn(true);
        when(strategyConfig.getMaxAutoRestarts()).thenReturn(5);

        // Live execution that hit target
        StrategyExecution execution = createExecution("exec-3", "user-3", StrategyConstants.TRADING_MODE_LIVE);
        execution.setStatus(StrategyStatus.COMPLETED);
        execution.setCompletionReason(StrategyCompletionReason.TARGET_HIT);

        // When: Event listener is invoked
        StrategyService.StrategyCompletionEvent event = new StrategyService.StrategyCompletionEvent(this, execution);
        scheduler.onStrategyCompletion(event);

        // Then: Scheduler should schedule a task
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(java.time.Instant.class));
    }

    @Test
    void testManualStopDoesNotScheduleRestart() {
        // Given: Auto-restart enabled for paper
        when(strategyConfig.isAutoRestartEnabled()).thenReturn(true);
        when(strategyConfig.isAutoRestartPaperEnabled()).thenReturn(true);

        // Execution stopped manually
        StrategyExecution execution = createExecution("exec-4", "user-4", StrategyConstants.TRADING_MODE_PAPER);
        execution.setStatus(StrategyStatus.COMPLETED);
        execution.setCompletionReason(StrategyCompletionReason.MANUAL_STOP);

        // When: Event listener is invoked
        StrategyService.StrategyCompletionEvent event = new StrategyService.StrategyCompletionEvent(this, execution);
        scheduler.onStrategyCompletion(event);

        // Then: Scheduler should NOT schedule (manual stop not eligible)
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(java.time.Instant.class));
    }

    @Test
    void testMaxRestartLimitPreventsScheduling() {
        // Given: Max restarts = 2, execution already restarted 2 times
        when(strategyConfig.isAutoRestartEnabled()).thenReturn(true);
        when(strategyConfig.isAutoRestartPaperEnabled()).thenReturn(true);
        when(strategyConfig.getMaxAutoRestarts()).thenReturn(2);

        StrategyExecution execution = createExecution("exec-5", "user-5", StrategyConstants.TRADING_MODE_PAPER);
        execution.setStatus(StrategyStatus.COMPLETED);
        execution.setCompletionReason(StrategyCompletionReason.TARGET_HIT);
        execution.setAutoRestartCount(2); // Already at limit

        // When: Event listener is invoked
        StrategyService.StrategyCompletionEvent event = new StrategyService.StrategyCompletionEvent(this, execution);
        scheduler.onStrategyCompletion(event);

        // Then: Scheduler should NOT schedule (limit reached)
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(java.time.Instant.class));
    }

    @Test
    void testDailyPnlGateBlocksRestartForSellATMStraddle() {
        // Given: Auto-restart enabled, daily profit limit breached
        when(strategyConfig.isAutoRestartEnabled()).thenReturn(true);
        when(strategyConfig.isAutoRestartPaperEnabled()).thenReturn(true);
        when(strategyConfig.getMaxAutoRestarts()).thenReturn(0);

        StrategyExecution execution = createExecution("exec-6", "user-6", StrategyConstants.TRADING_MODE_PAPER);
        execution.setStrategyType(StrategyType.SELL_ATM_STRADDLE);
        execution.setStatus(StrategyStatus.COMPLETED);
        execution.setCompletionReason(StrategyCompletionReason.TARGET_HIT);

        // Daily P&L gate is breached
        when(dailyPnlGateService.getBreachReason("user-6"))
                .thenReturn(Optional.of(StrategyCompletionReason.DAY_PROFIT_LIMIT_HIT));
        when(dailyPnlGateService.getDailyPnl("user-6")).thenReturn(BigDecimal.valueOf(5000));

        // When
        scheduler.onStrategyCompletion(new StrategyService.StrategyCompletionEvent(this, execution));

        // Then: Restart should NOT be scheduled and bot should be stopped
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(java.time.Instant.class));
        verify(botStatusService, times(1)).markStopped();
    }

    @Test
    void testDailyPnlGateDoesNotBlockATMStraddle() {
        // Given: Auto-restart enabled, daily profit limit breached BUT strategy is ATM_STRADDLE (not SELL)
        when(strategyConfig.isAutoRestartEnabled()).thenReturn(true);
        when(strategyConfig.isAutoRestartPaperEnabled()).thenReturn(true);
        when(strategyConfig.getMaxAutoRestarts()).thenReturn(0);

        StrategyExecution execution = createExecution("exec-7", "user-7", StrategyConstants.TRADING_MODE_PAPER);
        execution.setStrategyType(StrategyType.ATM_STRADDLE); // Not SELL_ATM_STRADDLE
        execution.setStatus(StrategyStatus.COMPLETED);
        execution.setCompletionReason(StrategyCompletionReason.TARGET_HIT);

        // Daily P&L gate is breached but should be ignored for ATM_STRADDLE
        when(dailyPnlGateService.getBreachReason("user-7"))
                .thenReturn(Optional.of(StrategyCompletionReason.DAY_PROFIT_LIMIT_HIT));

        // When
        scheduler.onStrategyCompletion(new StrategyService.StrategyCompletionEvent(this, execution));

        // Then: Gate should NOT block ATM_STRADDLE — bot should NOT be stopped.
        // (Whether schedule() is actually called depends on market hours, which is not the concern of this test.)
        verify(botStatusService, never()).markStopped();
    }

    @Test
    void testDailyLossLimitBlocksRestart() {
        // Given: Auto-restart enabled, daily loss limit breached
        when(strategyConfig.isAutoRestartEnabled()).thenReturn(true);
        when(strategyConfig.isAutoRestartPaperEnabled()).thenReturn(true);
        when(strategyConfig.getMaxAutoRestarts()).thenReturn(0);

        StrategyExecution execution = createExecution("exec-8", "user-8", StrategyConstants.TRADING_MODE_PAPER);
        execution.setStrategyType(StrategyType.SELL_ATM_STRADDLE);
        execution.setStatus(StrategyStatus.COMPLETED);
        execution.setCompletionReason(StrategyCompletionReason.STOPLOSS_HIT);

        // Daily P&L gate: loss limit breached
        when(dailyPnlGateService.getBreachReason("user-8"))
                .thenReturn(Optional.of(StrategyCompletionReason.DAY_LOSS_LIMIT_HIT));
        when(dailyPnlGateService.getDailyPnl("user-8")).thenReturn(BigDecimal.valueOf(-3000));

        // When
        scheduler.onStrategyCompletion(new StrategyService.StrategyCompletionEvent(this, execution));

        // Then: Restart blocked, bot stopped
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(java.time.Instant.class));
        verify(botStatusService, times(1)).markStopped();
    }

    @Test
    void testNoPnlGateBreachAllowsRestart() {
        // Given: Auto-restart enabled, no daily P&L breach
        when(strategyConfig.isAutoRestartEnabled()).thenReturn(true);
        when(strategyConfig.isAutoRestartPaperEnabled()).thenReturn(true);
        when(strategyConfig.getMaxAutoRestarts()).thenReturn(0);

        StrategyExecution execution = createExecution("exec-9", "user-9", StrategyConstants.TRADING_MODE_PAPER);
        execution.setStrategyType(StrategyType.SELL_ATM_STRADDLE);
        execution.setStatus(StrategyStatus.COMPLETED);
        execution.setCompletionReason(StrategyCompletionReason.TARGET_HIT);

        // No breach
        when(dailyPnlGateService.getBreachReason("user-9")).thenReturn(Optional.empty());

        // When
        scheduler.onStrategyCompletion(new StrategyService.StrategyCompletionEvent(this, execution));

        // Then: Gate should NOT block — bot should NOT be stopped.
        // (Whether schedule() is actually called depends on market hours, which is not the concern of this test.)
        verify(botStatusService, never()).markStopped();
    }

    private StrategyExecution createExecution(String executionId, String userId, String tradingMode) {
        StrategyExecution execution = new StrategyExecution();
        execution.setExecutionId(executionId);
        execution.setUserId(userId);
        execution.setTradingMode(tradingMode);
        execution.setStrategyType(StrategyType.ATM_STRADDLE);
        execution.setInstrumentType("NIFTY");
        execution.setExpiry("2025-11-20");
        execution.setAutoRestartCount(0);
        execution.setRootExecutionId(executionId);
        return execution;
    }
}

